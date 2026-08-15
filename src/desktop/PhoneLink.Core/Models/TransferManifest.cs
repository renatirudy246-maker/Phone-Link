namespace PhoneLink.Core.Models;

public enum TransferPurpose
{
    Photo = 1,
    Question = 2,
    File = 3,
}

public sealed record TransferManifest(
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
    TransferPurpose Purpose);