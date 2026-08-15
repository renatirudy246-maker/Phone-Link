using PhoneLink.Core.Errors;
using PhoneLink.Core.Models;

namespace PhoneLink.Core.Transfers;

public sealed record TransferResult(TransferRecord? Record, ApiError? Error)
{
    public bool IsSuccess => Error is null;
}

public interface ITransferService
{
    Task<TransferResult> ReceiveAsync(TransferManifest manifest, Stream file, CancellationToken cancellationToken);

    Task<TransferRecord?> GetByIdAsync(string transferId, CancellationToken cancellationToken);

    Task<IReadOnlyList<TransferRecord>> GetRecentAsync(int limit, CancellationToken cancellationToken);
}