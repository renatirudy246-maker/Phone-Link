using PhoneLink.Core.Errors;

namespace PhoneLink.Core.Transfers;

public sealed record FileWriteResult(string LocalFilePath, string ActualSha256, long ActualSizeBytes);

public interface ITransferFileStore
{
    Task<FileWriteResult> WriteAsync(
        Stream source,
        string transferId,
        string declaredSha256,
        string declaredMimeType,
        long maxBytes,
        CancellationToken cancellationToken);
}

public sealed class TransferProcessingException : Exception
{
    public ApiError Error { get; }

    public TransferProcessingException(ApiError error)
        : base(error.Message)
    {
        Error = error;
    }
}