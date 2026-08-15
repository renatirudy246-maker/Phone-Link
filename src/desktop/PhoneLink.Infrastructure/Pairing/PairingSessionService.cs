using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Logging;
using PhoneLink.Core;
using PhoneLink.Core.Auth;
using PhoneLink.Core.Errors;
using PhoneLink.Core.Identity;
using PhoneLink.Core.Models;
using PhoneLink.Core.Pairing;
using PhoneLink.Core.Security;
using PhoneLink.Infrastructure.Storage;

namespace PhoneLink.Infrastructure.Pairing;

/// <summary>
/// 正式 PairingSession：一次性 Token（256-bit，仅存 SHA-256 哈希）、3 分钟有效期、
/// 消费后立即标记 consumed（重放返回 PAIR_ALREADY_USED），过期/消费后由清理机制删除。
/// </summary>
public sealed class PairingSessionService : IPairingSessionService
{
    public static readonly TimeSpan SessionTtl = TimeSpan.FromMinutes(3);

    private readonly PhoneLinkDb _db;
    private readonly IDeviceIdentityProvider _deviceIdentity;
    private readonly ITlsCertificateProvider _certificateProvider;
    private readonly string _lanIp;
    private readonly int _port;
    private readonly ILogger<PairingSessionService> _logger;

    public PairingSessionService(
        PhoneLinkDb db,
        IDeviceIdentityProvider deviceIdentity,
        ITlsCertificateProvider certificateProvider,
        string lanIp,
        int port,
        ILogger<PairingSessionService> logger)
    {
        _db = db;
        _deviceIdentity = deviceIdentity;
        _certificateProvider = certificateProvider;
        _lanIp = lanIp;
        _port = port;
        _logger = logger;
    }

    public async Task<CreatePairingSessionResult> CreateAsync(CancellationToken cancellationToken)
    {
        await CleanupExpiredAsync(cancellationToken).ConfigureAwait(false);

        var identity = await _deviceIdentity.GetIdentityAsync(cancellationToken).ConfigureAwait(false);
        var certificate = _certificateProvider.GetOrCreateCertificate();
        var oneTimeToken = TokenGenerator.GenerateSecureToken();
        var session = new PairingSession(
            SessionId: $"ps-{Guid.NewGuid():N}",
            OneTimeToken: oneTimeToken,
            ExpiresAt: DateTimeOffset.UtcNow.Add(SessionTtl),
            DesktopDeviceId: identity.DeviceId,
            DesktopDisplayName: identity.DisplayName,
            Endpoint: $"{_lanIp}:{_port}",
            CertificateFingerprint: CertificateFingerprint.ComputeSha256Hex(certificate),
            Consumed: false);

        await using var connection = _db.OpenConnection();
        await using (var command = connection.CreateCommand())
        {
            command.CommandText = """
                INSERT INTO pairing_sessions
                    (session_id, one_time_token_hash, expires_at, desktop_device_id,
                     desktop_display_name, endpoint, certificate_fingerprint, consumed, created_at)
                VALUES ($session_id, $token_hash, $expires_at, $device_id,
                        $display_name, $endpoint, $fingerprint, 0, $created_at);
                """;
            command.Parameters.AddWithValue("$session_id", session.SessionId);
            command.Parameters.AddWithValue("$token_hash", HashToken(oneTimeToken));
            command.Parameters.AddWithValue("$expires_at", session.ExpiresAt.ToString("O"));
            command.Parameters.AddWithValue("$device_id", session.DesktopDeviceId);
            command.Parameters.AddWithValue("$display_name", session.DesktopDisplayName);
            command.Parameters.AddWithValue("$endpoint", session.Endpoint);
            command.Parameters.AddWithValue("$fingerprint", session.CertificateFingerprint);
            command.Parameters.AddWithValue("$created_at", DateTimeOffset.UtcNow.ToString("O"));
            await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        }

        _logger.LogInformation("Pairing session created: {SessionId} (expires in {TtlSeconds}s).",
            Ids.Short(session.SessionId), (int)SessionTtl.TotalSeconds);

        var payload = new PairingQrPayload(
            ProtocolVersion: AppInfo.ProtocolVersion,
            DesktopDeviceId: identity.DeviceId,
            DesktopDeviceName: identity.DisplayName,
            Host: _lanIp,
            Port: _port,
            OneTimeToken: oneTimeToken,
            CertificateFingerprint: session.CertificateFingerprint,
            ExpiresAt: session.ExpiresAt);

        return new CreatePairingSessionResult(session, PairingQrCodec.Encode(payload));
    }

    public async Task<ConsumePairingSessionResult> ConsumeAsync(string oneTimeToken, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(oneTimeToken) || oneTimeToken.Length > 128)
        {
            return new ConsumePairingSessionResult(null, ErrorCodes.PairTokenInvalid);
        }

        var tokenHash = HashToken(oneTimeToken);
        await using var connection = _db.OpenConnection();

        PairingSession? session = null;
        await using (var command = connection.CreateCommand())
        {
            command.CommandText = """
                SELECT session_id, expires_at, desktop_device_id, desktop_display_name,
                       endpoint, certificate_fingerprint, consumed
                FROM pairing_sessions
                WHERE one_time_token_hash = $token_hash;
                """;
            command.Parameters.AddWithValue("$token_hash", tokenHash);
            await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
            if (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
            {
                session = new PairingSession(
                    SessionId: reader.GetString(0),
                    OneTimeToken: oneTimeToken,
                    ExpiresAt: DateTimeOffset.Parse(reader.GetString(1)),
                    DesktopDeviceId: reader.GetString(2),
                    DesktopDisplayName: reader.GetString(3),
                    Endpoint: reader.GetString(4),
                    CertificateFingerprint: reader.GetString(5),
                    Consumed: reader.GetInt64(6) != 0);
            }
        }

        if (session is null)
        {
            _logger.LogWarning("Pairing attempt with unknown token.");
            return new ConsumePairingSessionResult(null, ErrorCodes.PairTokenInvalid);
        }

        if (session.Consumed)
        {
            _logger.LogWarning("Pairing token reuse rejected: {SessionId}.", Ids.Short(session.SessionId));
            return new ConsumePairingSessionResult(null, ErrorCodes.PairAlreadyUsed);
        }

        if (session.ExpiresAt <= DateTimeOffset.UtcNow)
        {
            _logger.LogWarning("Pairing token expired: {SessionId}.", Ids.Short(session.SessionId));
            return new ConsumePairingSessionResult(null, ErrorCodes.PairTokenExpired);
        }

        await using (var command = connection.CreateCommand())
        {
            command.CommandText = "UPDATE pairing_sessions SET consumed = 1 WHERE session_id = $session_id;";
            command.Parameters.AddWithValue("$session_id", session.SessionId);
            await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        }

        _logger.LogInformation("Pairing session consumed: {SessionId}.", Ids.Short(session.SessionId));
        await CleanupExpiredAsync(cancellationToken).ConfigureAwait(false);
        return new ConsumePairingSessionResult(session with { Consumed = true }, null);
    }

    public async Task<int> CleanupExpiredAsync(CancellationToken cancellationToken)
    {
        await using var connection = _db.OpenConnection();
        await using var command = connection.CreateCommand();
        command.CommandText = "DELETE FROM pairing_sessions WHERE expires_at <= $now;";
        command.Parameters.AddWithValue("$now", DateTimeOffset.UtcNow.ToString("O"));
        return await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    internal static string HashToken(string token)
    {
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token)));
    }
}