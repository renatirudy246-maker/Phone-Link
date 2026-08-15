using System.Security.Cryptography;
using System.Text;
using PhoneLink.Core.Auth;
using PhoneLink.Core.Errors;
using PhoneLink.Core.Pairing;
using PhoneLink.Infrastructure.Storage;

namespace PhoneLink.Infrastructure.Auth;

/// <summary>
/// 正式 Device Token 验证器：SHA-256(token) 与库中哈希做 constant-time 比较。
/// 已撤销设备返回 DEVICE_REVOKED。Phase 1 DevTokenValidator 已移除，无任何开发认证后门。
/// </summary>
public sealed class PairedDeviceTokenValidator : ITokenValidator
{
    private readonly PhoneLinkDb _db;
    private readonly IPairedDeviceRepository _repository;

    public PairedDeviceTokenValidator(PhoneLinkDb db, IPairedDeviceRepository repository)
    {
        _db = db;
        _repository = repository;
    }

    public async Task<TokenValidationResult> ValidateAsync(string? bearerToken, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(bearerToken) || bearerToken.Length > 128)
        {
            return new TokenValidationResult(false, ErrorCode: ErrorCodes.AuthInvalid);
        }

        var tokenHash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(bearerToken)));

        string? deviceId = null;
        string? displayName = null;
        string? platform = null;
        bool isTrusted = false;

        await using (var connection = _db.OpenConnection())
        {
            await using var command = connection.CreateCommand();
            command.CommandText = """
                SELECT device_id, display_name, platform, is_trusted
                FROM paired_devices
                WHERE token_hash = $token_hash;
                """;
            command.Parameters.AddWithValue("$token_hash", tokenHash);
            await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);

            if (!await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
            {
                return new TokenValidationResult(false, ErrorCode: ErrorCodes.AuthInvalid);
            }

            deviceId = reader.GetString(0);
            displayName = reader.GetString(1);
            platform = reader.GetString(2);
            isTrusted = reader.GetInt64(3) != 0;
        }

        if (!isTrusted)
        {
            return new TokenValidationResult(false, DeviceId: deviceId, ErrorCode: ErrorCodes.DeviceRevoked);
        }

        // 注意：reader/connection 已释放后才做写入，避免 SQLite 读锁阻塞写锁（database is locked）。
        await _repository.SetLastSeenAsync(deviceId!, DateTimeOffset.UtcNow, null, cancellationToken).ConfigureAwait(false);
        return new TokenValidationResult(true, deviceId, displayName, platform);
    }
}