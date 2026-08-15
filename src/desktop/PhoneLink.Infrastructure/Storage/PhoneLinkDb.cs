using Microsoft.Data.Sqlite;
using PhoneLink.Infrastructure.Paths;

namespace PhoneLink.Infrastructure.Storage;

public sealed class PhoneLinkDb
{
    private readonly string _connectionString;

    public PhoneLinkDb(AppPaths paths)
    {
        _connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = paths.DbPath,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Pooling = false,
        }.ToString();
        Initialize();
    }

    public SqliteConnection OpenConnection()
    {
        var connection = new SqliteConnection(_connectionString);
        connection.Open();
        return connection;
    }

    private void Initialize()
    {
        using var connection = OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = """
            CREATE TABLE IF NOT EXISTS transfers (
                transfer_id        TEXT PRIMARY KEY,
                sender_device_id   TEXT NOT NULL,
                original_file_name TEXT NOT NULL,
                mime_type          TEXT NOT NULL,
                file_size          INTEGER NOT NULL,
                width              INTEGER,
                height             INTEGER,
                sha256             TEXT NOT NULL,
                captured_at        TEXT NOT NULL,
                sent_at            TEXT NOT NULL,
                purpose            INTEGER NOT NULL,
                local_file_path    TEXT NOT NULL,
                thumbnail_path     TEXT,
                received_at        TEXT NOT NULL,
                status             INTEGER NOT NULL,
                error_code         TEXT
            );

            CREATE TABLE IF NOT EXISTS settings (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS pairing_sessions (
                session_id             TEXT PRIMARY KEY,
                one_time_token_hash    TEXT NOT NULL UNIQUE,
                expires_at             TEXT NOT NULL,
                desktop_device_id      TEXT NOT NULL,
                desktop_display_name   TEXT NOT NULL,
                endpoint               TEXT NOT NULL,
                certificate_fingerprint TEXT NOT NULL,
                consumed               INTEGER NOT NULL DEFAULT 0,
                created_at             TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS paired_devices (
                device_id              TEXT PRIMARY KEY,
                display_name           TEXT NOT NULL,
                platform               TEXT NOT NULL,
                token_hash             TEXT NOT NULL,
                certificate_fingerprint TEXT NOT NULL,
                last_seen_at           TEXT,
                last_known_endpoint    TEXT,
                is_trusted             INTEGER NOT NULL DEFAULT 1,
                paired_at              TEXT NOT NULL
            );
            """;
        command.ExecuteNonQuery();
    }
}