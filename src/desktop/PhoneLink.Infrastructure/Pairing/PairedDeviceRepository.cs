using Microsoft.Extensions.Logging;
using PhoneLink.Core;
using PhoneLink.Core.Models;
using PhoneLink.Core.Pairing;
using PhoneLink.Infrastructure.Storage;

namespace PhoneLink.Infrastructure.Pairing;

/// <summary>
/// 已配对设备持久化。服务端只保存 Device Token 的 SHA-256 哈希（AuthTokenReference），
/// 不保存裸 Token，数据库被复制也无法直接作为 Bearer 使用。
/// </summary>
public sealed class PairedDeviceRepository : IPairedDeviceRepository
{
    private readonly PhoneLinkDb _db;
    private readonly ILogger<PairedDeviceRepository> _logger;

    public PairedDeviceRepository(PhoneLinkDb db, ILogger<PairedDeviceRepository> logger)
    {
        _db = db;
        _logger = logger;
    }

    public async Task<PairedDevice?> GetByDeviceIdAsync(string deviceId, CancellationToken cancellationToken)
    {
        await using var connection = _db.OpenConnection();
        await using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT device_id, display_name, platform, token_hash, certificate_fingerprint,
                   last_seen_at, last_known_endpoint, is_trusted
            FROM paired_devices
            WHERE device_id = $device_id;
            """;
        command.Parameters.AddWithValue("$device_id", deviceId);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        return await reader.ReadAsync(cancellationToken).ConfigureAwait(false)
            ? ReadDevice(reader)
            : null;
    }

    public async Task<List<PairedDevice>> ListAllAsync(CancellationToken cancellationToken)
    {
        var devices = new List<PairedDevice>();
        await using var connection = _db.OpenConnection();
        await using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT device_id, display_name, platform, token_hash, certificate_fingerprint,
                   last_seen_at, last_known_endpoint, is_trusted
            FROM paired_devices
            ORDER BY last_seen_at DESC;
            """;
        await using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
        while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
        {
            devices.Add(ReadDevice(reader));
        }

        return devices;
    }

    public async Task UpsertAsync(PairedDevice device, CancellationToken cancellationToken)
    {
        await using var connection = _db.OpenConnection();
        await using var command = connection.CreateCommand();
        command.CommandText = """
            INSERT INTO paired_devices
                (device_id, display_name, platform, token_hash, certificate_fingerprint,
                 last_seen_at, last_known_endpoint, is_trusted, paired_at)
            VALUES ($device_id, $display_name, $platform, $token_hash, $fingerprint,
                    $last_seen_at, $last_known_endpoint, $is_trusted, $paired_at)
            ON CONFLICT(device_id) DO UPDATE SET
                display_name = $display_name,
                platform = $platform,
                token_hash = $token_hash,
                certificate_fingerprint = $fingerprint,
                is_trusted = $is_trusted;
            """;
        command.Parameters.AddWithValue("$device_id", device.DeviceId);
        command.Parameters.AddWithValue("$display_name", device.DisplayName);
        command.Parameters.AddWithValue("$platform", device.Platform);
        command.Parameters.AddWithValue("$token_hash", device.AuthTokenReference);
        command.Parameters.AddWithValue("$fingerprint", device.CertificateFingerprint);
        command.Parameters.AddWithValue("$last_seen_at", (object?)device.LastSeenAt?.ToString("O") ?? DBNull.Value);
        command.Parameters.AddWithValue("$last_known_endpoint", (object?)device.LastKnownEndpoint ?? DBNull.Value);
        command.Parameters.AddWithValue("$is_trusted", device.IsTrusted ? 1 : 0);
        command.Parameters.AddWithValue("$paired_at", DateTimeOffset.UtcNow.ToString("O"));
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);

        _logger.LogInformation("Paired device upserted: {DeviceId}.", Ids.Short(device.DeviceId));
    }

    public async Task SetLastSeenAsync(
        string deviceId,
        DateTimeOffset lastSeenAt,
        string? lastKnownEndpoint,
        CancellationToken cancellationToken)
    {
        await using var connection = _db.OpenConnection();
        await using var command = connection.CreateCommand();
        command.CommandText = """
            UPDATE paired_devices
            SET last_seen_at = $last_seen_at, last_known_endpoint = $last_known_endpoint
            WHERE device_id = $device_id;
            """;
        command.Parameters.AddWithValue("$last_seen_at", lastSeenAt.ToString("O"));
        command.Parameters.AddWithValue("$last_known_endpoint", (object?)lastKnownEndpoint ?? DBNull.Value);
        command.Parameters.AddWithValue("$device_id", deviceId);
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    public async Task SetRevokedAsync(string deviceId, bool revoked, CancellationToken cancellationToken)
    {
        await using var connection = _db.OpenConnection();
        await using var command = connection.CreateCommand();
        command.CommandText = "UPDATE paired_devices SET is_trusted = $is_trusted WHERE device_id = $device_id;";
        command.Parameters.AddWithValue("$is_trusted", revoked ? 0 : 1);
        command.Parameters.AddWithValue("$device_id", deviceId);
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);

        _logger.LogWarning("Paired device {DeviceId} revoked: {Revoked}.", Ids.Short(deviceId), revoked);
    }

    private static PairedDevice ReadDevice(Microsoft.Data.Sqlite.SqliteDataReader reader)
    {
        return new PairedDevice(
            DeviceId: reader.GetString(0),
            DisplayName: reader.GetString(1),
            Platform: reader.GetString(2),
            AuthTokenReference: reader.GetString(3),
            CertificateFingerprint: reader.GetString(4),
            LastSeenAt: reader.IsDBNull(5) ? null : DateTimeOffset.Parse(reader.GetString(5)),
            LastKnownEndpoint: reader.IsDBNull(6) ? null : reader.GetString(6),
            IsTrusted: reader.GetInt64(7) != 0);
    }
}