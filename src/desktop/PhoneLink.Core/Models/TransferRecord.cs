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
    string LocalFilePath,
    string ThumbnailPath,
    DateTimeOffset ReceivedAt,
    TransferStatus Status,
    string? ErrorCode);