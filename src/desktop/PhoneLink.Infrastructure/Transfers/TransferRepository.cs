using Microsoft.Data.Sqlite;
using PhoneLink.Core.Models;
using PhoneLink.Core.Transfers;
using PhoneLink.Infrastructure.Storage;

namespace PhoneLink.Infrastructure.Transfers;

public sealed class TransferRepository : ITransferRepository
{
    private readonly PhoneLinkDb _db;

    public TransferRepository(PhoneLinkDb db)
    {
        _db = db;
    }

    public async Task<TransferRecord?> GetByIdAsync(string transferId, CancellationToken cancellationToken)
    {
        using var connection = _db.OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT * FROM transfers WHERE transfer_id = $id;";
        command.Parameters.AddWithValue("$id", transferId);

        using var reader = await command.ExecuteReaderAsync(cancellationToken);
        return reader.Read() ? Map(reader) : null;
    }

    public async Task AddAsync(TransferRecord record, CancellationToken cancellationToken)
    {
        using var connection = _db.OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = """
            INSERT INTO transfers (
                transfer_id, sender_device_id, original_file_name, mime_type, file_size,
                width, height, sha256, captured_at, sent_at, purpose,
                local_file_path, thumbnail_path, received_at, status, error_code)
            VALUES (
                $transfer_id, $sender_device_id, $original_file_name, $mime_type, $file_size,
                $width, $height, $sha256, $captured_at, $sent_at, $purpose,
                $local_file_path, $thumbnail_path, $received_at, $status, $error_code);
            """;
        command.Parameters.AddWithValue("$transfer_id", record.TransferId);
        command.Parameters.AddWithValue("$sender_device_id", record.SenderDeviceId);
        command.Parameters.AddWithValue("$original_file_name", record.OriginalFileName);
        command.Parameters.AddWithValue("$mime_type", record.MimeType);
        command.Parameters.AddWithValue("$file_size", record.FileSize);
        command.Parameters.AddWithValue("$width", (object?)record.Width ?? DBNull.Value);
        command.Parameters.AddWithValue("$height", (object?)record.Height ?? DBNull.Value);
        command.Parameters.AddWithValue("$sha256", record.Sha256);
        command.Parameters.AddWithValue("$captured_at", record.CapturedAt.ToString("O"));
        command.Parameters.AddWithValue("$sent_at", record.SentAt.ToString("O"));
        command.Parameters.AddWithValue("$purpose", (int)record.Purpose);
        command.Parameters.AddWithValue("$local_file_path", record.LocalFilePath);
        command.Parameters.AddWithValue("$thumbnail_path", (object?)record.ThumbnailPath ?? DBNull.Value);
        command.Parameters.AddWithValue("$received_at", record.ReceivedAt.ToString("O"));
        command.Parameters.AddWithValue("$status", (int)record.Status);
        command.Parameters.AddWithValue("$error_code", (object?)record.ErrorCode ?? DBNull.Value);
        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    public async Task UpdateStatusAsync(
        string transferId,
        TransferStatus status,
        string? errorCode,
        CancellationToken cancellationToken)
    {
        using var connection = _db.OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = "UPDATE transfers SET status = $status, error_code = $error_code WHERE transfer_id = $id;";
        command.Parameters.AddWithValue("$status", (int)status);
        command.Parameters.AddWithValue("$error_code", (object?)errorCode ?? DBNull.Value);
        command.Parameters.AddWithValue("$id", transferId);
        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    public async Task<IReadOnlyList<TransferRecord>> GetRecentAsync(int limit, CancellationToken cancellationToken)
    {
        using var connection = _db.OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT * FROM transfers ORDER BY received_at DESC LIMIT $limit;";
        command.Parameters.AddWithValue("$limit", limit);

        var results = new List<TransferRecord>();
        using var reader = await command.ExecuteReaderAsync(cancellationToken);
        while (await reader.ReadAsync(cancellationToken))
        {
            results.Add(Map(reader));
        }

        return results;
    }

    private static TransferRecord Map(SqliteDataReader reader)
    {
        static DateTimeOffset ReadOffset(SqliteDataReader r, int index)
            => DateTimeOffset.Parse((string)r.GetValue(index));

        static int? ReadNullableInt(SqliteDataReader r, int index)
            => r.IsDBNull(index) ? null : r.GetInt32(index);

        static string? ReadNullableString(SqliteDataReader r, int index)
            => r.IsDBNull(index) ? null : r.GetString(index);

        return new TransferRecord(
            TransferId: reader.GetString(reader.GetOrdinal("transfer_id")),
            SenderDeviceId: reader.GetString(reader.GetOrdinal("sender_device_id")),
            OriginalFileName: reader.GetString(reader.GetOrdinal("original_file_name")),
            MimeType: reader.GetString(reader.GetOrdinal("mime_type")),
            FileSize: reader.GetInt64(reader.GetOrdinal("file_size")),
            Width: ReadNullableInt(reader, reader.GetOrdinal("width")),
            Height: ReadNullableInt(reader, reader.GetOrdinal("height")),
            Sha256: reader.GetString(reader.GetOrdinal("sha256")),
            CapturedAt: ReadOffset(reader, reader.GetOrdinal("captured_at")),
            SentAt: ReadOffset(reader, reader.GetOrdinal("sent_at")),
            Purpose: (TransferPurpose)reader.GetInt32(reader.GetOrdinal("purpose")),
            LocalFilePath: reader.GetString(reader.GetOrdinal("local_file_path")),
            ThumbnailPath: ReadNullableString(reader, reader.GetOrdinal("thumbnail_path")),
            ReceivedAt: ReadOffset(reader, reader.GetOrdinal("received_at")),
            Status: (TransferStatus)reader.GetInt32(reader.GetOrdinal("status")),
            ErrorCode: ReadNullableString(reader, reader.GetOrdinal("error_code")));
    }
}