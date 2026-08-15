using PhoneLink.Core.Models;

namespace PhoneLink.Core.Transfers;

public interface ITransferRepository
{
    Task<TransferRecord?> GetByIdAsync(string transferId, CancellationToken cancellationToken);

    Task AddAsync(TransferRecord record, CancellationToken cancellationToken);

    Task UpdateStatusAsync(
        string transferId,
        TransferStatus status,
        string? errorCode,
        CancellationToken cancellationToken);

    Task<IReadOnlyList<TransferRecord>> GetRecentAsync(int limit, CancellationToken cancellationToken);
}