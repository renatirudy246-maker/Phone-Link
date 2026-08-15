namespace PhoneLink.Core.Models;

public enum TransferStatus
{
    Receiving = 1,
    Completed = 2,
    Failed = 3,
    Deleted = 4,
}

public sealed record TransferRecord(
    string TransferId,
    string SenderDeviceId,
    string OriginalFileName,
    string MimeType,
    long FileSize,
    int? Width,
    int? Height,
    string Sha256,
    DateTimeOffset CapturedAt,
    DateTimeOffset SentAt,
    TransferPurpose Purpose,
    string LocalFilePath,
    string? ThumbnailPath,
    DateTimeOffset ReceivedAt,
    TransferStatus Status,
    string? ErrorCode);