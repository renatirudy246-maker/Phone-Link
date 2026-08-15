using Microsoft.Extensions.Logging;
using PhoneLink.Core;
using PhoneLink.Core.Errors;
using PhoneLink.Core.Models;
using PhoneLink.Core.Transfers;

namespace PhoneLink.Infrastructure.Transfers;

public sealed class TransferService : ITransferService
{
    private readonly ITransferRepository _repository;
    private readonly ITransferFileStore _fileStore;
    private readonly ITransferEventPublisher _events;
    private readonly ILogger<TransferService> _logger;

    public TransferService(
        ITransferRepository repository,
        ITransferFileStore fileStore,
        ITransferEventPublisher events,
        ILogger<TransferService> logger)
    {
        _repository = repository;
        _fileStore = fileStore;
        _events = events;
        _logger = logger;
    }

    public async Task<TransferResult> ReceiveAsync(
        TransferManifest manifest,
        Stream file,
        CancellationToken cancellationToken)
    {
        var existing = await _repository.GetByIdAsync(manifest.TransferId, cancellationToken);
        if (existing is { Status: TransferStatus.Completed })
        {
            _logger.LogInformation(
                "Transfer {TransferId} from {Sender} already completed; returning existing record (idempotent).",
                Ids.Short(manifest.TransferId), Ids.Short(manifest.SenderDeviceId));
            return new TransferResult(existing, null);
        }

        try
        {
            var fileResult = await _fileStore.WriteAsync(
                file,
                manifest.TransferId,
                manifest.Sha256,
                manifest.MimeType,
                AppInfo.MaxImageSizeBytes,
                cancellationToken);

            var record = new TransferRecord(
                TransferId: manifest.TransferId,
                SenderDeviceId: manifest.SenderDeviceId,
                OriginalFileName: manifest.OriginalFileName,
                MimeType: manifest.MimeType,
                FileSize: fileResult.ActualSizeBytes,
                Width: manifest.Width,
                Height: manifest.Height,
                Sha256: fileResult.ActualSha256,
                CapturedAt: manifest.CapturedAt,
                SentAt: manifest.SentAt,
                Purpose: manifest.Purpose,
                LocalFilePath: fileResult.LocalFilePath,
                ThumbnailPath: null,
                ReceivedAt: DateTimeOffset.UtcNow,
                Status: TransferStatus.Completed,
                ErrorCode: null);

            try
            {
                await _repository.AddAsync(record, cancellationToken);
            }
            catch (Exception ex) when (ex is IOException or InvalidOperationException)
            {
                TryDelete(fileResult.LocalFilePath);
                throw new TransferProcessingException(new ApiError(
                    ErrorCodes.DiskWriteFailed, "Failed to persist transfer record.", Retryable: true));
            }

            _events.Publish(record);
            _logger.LogInformation(
                "Transfer {TransferId} completed ({Size} bytes, {Mime}) -> {File}",
                Ids.Short(manifest.TransferId), fileResult.ActualSizeBytes, manifest.MimeType, fileResult.LocalFilePath);
            return new TransferResult(record, null);
        }
        catch (TransferProcessingException ex)
        {
            await RecordFailureAsync(manifest, ex.Error, cancellationToken);
            _logger.LogWarning(
                "Transfer {TransferId} failed: {Code}",
                Ids.Short(manifest.TransferId), ex.Error.Code);
            return new TransferResult(null, ex.Error);
        }
    }

    public Task<TransferRecord?> GetByIdAsync(string transferId, CancellationToken cancellationToken)
        => _repository.GetByIdAsync(transferId, cancellationToken);

    public Task<IReadOnlyList<TransferRecord>> GetRecentAsync(int limit, CancellationToken cancellationToken)
        => _repository.GetRecentAsync(limit, cancellationToken);

    private async Task RecordFailureAsync(
        TransferManifest manifest,
        ApiError error,
        CancellationToken cancellationToken)
    {
        try
        {
            var failed = new TransferRecord(
                TransferId: manifest.TransferId,
                SenderDeviceId: manifest.SenderDeviceId,
                OriginalFileName: manifest.OriginalFileName,
                MimeType: manifest.MimeType,
                FileSize: 0,
                Width: manifest.Width,
                Height: manifest.Height,
                Sha256: manifest.Sha256,
                CapturedAt: manifest.CapturedAt,
                SentAt: manifest.SentAt,
                Purpose: manifest.Purpose,
                LocalFilePath: string.Empty,
                ThumbnailPath: null,
                ReceivedAt: DateTimeOffset.UtcNow,
                Status: TransferStatus.Failed,
                ErrorCode: error.Code);

            var existing = await _repository.GetByIdAsync(manifest.TransferId, cancellationToken);
            if (existing is null)
            {
                await _repository.AddAsync(failed, cancellationToken);
            }
            else if (existing.Status != TransferStatus.Completed)
            {
                await _repository.UpdateStatusAsync(
                    manifest.TransferId, TransferStatus.Failed, error.Code, cancellationToken);
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to persist failure record for transfer {TransferId}",
                Ids.Short(manifest.TransferId));
        }
    }

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch
        {
            // 尽力清理。
        }
    }
}