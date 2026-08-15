using Microsoft.Data.Sqlite;
using PhoneLink.Core.Identity;
using PhoneLink.Core.Models;
using PhoneLink.Infrastructure.Storage;

namespace PhoneLink.Infrastructure.Identity;

public sealed class SqliteDeviceIdentityProvider : IDeviceIdentityProvider
{
    private const string DeviceIdKey = "device_id";
    private const string CreatedAtKey = "device_created_at";

    private readonly PhoneLinkDb _db;
    private readonly Lazy<DeviceIdentity> _identity;

    public SqliteDeviceIdentityProvider(PhoneLinkDb db)
    {
        _db = db;
        _identity = new Lazy<DeviceIdentity>(LoadOrCreate);
    }

    public Task<DeviceIdentity> GetIdentityAsync(CancellationToken cancellationToken)
        => Task.FromResult(_identity.Value);

    private DeviceIdentity LoadOrCreate()
    {
        using var connection = _db.OpenConnection();

        var deviceId = GetSetting(connection, DeviceIdKey);
        var createdAtRaw = GetSetting(connection, CreatedAtKey);

        if (deviceId is not null && createdAtRaw is not null
            && DateTimeOffset.TryParse(createdAtRaw, out var createdAt))
        {
            return BuildIdentity(deviceId, createdAt);
        }

        deviceId = $"desktop-{Guid.NewGuid():N}";
        createdAt = DateTimeOffset.UtcNow;
        UpsertSetting(connection, DeviceIdKey, deviceId);
        UpsertSetting(connection, CreatedAtKey, createdAt.ToString("O"));
        return BuildIdentity(deviceId, createdAt);
    }

    private static DeviceIdentity BuildIdentity(string deviceId, DateTimeOffset createdAt)
        => new(
            DeviceId: deviceId,
            DisplayName: Environment.MachineName,
            Platform: "windows",
            CreatedAt: createdAt,
            PublicFingerprint: string.Empty);

    private static string? GetSetting(SqliteConnection connection, string key)
    {
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT value FROM settings WHERE key = $key";
        command.Parameters.AddWithValue("$key", key);
        return command.ExecuteScalar() as string;
    }

    private static void UpsertSetting(SqliteConnection connection, string key, string value)
    {
        using var command = connection.CreateCommand();
        command.CommandText = """
            INSERT INTO settings (key, value) VALUES ($key, $value)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value;
            """;
        command.Parameters.AddWithValue("$key", key);
        command.Parameters.AddWithValue("$value", value);
        command.ExecuteNonQuery();
    }
}