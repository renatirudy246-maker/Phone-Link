using PhoneLink.Core.Errors;

namespace PhoneLink.Core.Feedback;

public sealed record ScannerFeedbackResult(bool AlreadyStored, ApiError? Error)
{
    public bool IsSuccess => Error is null;
}

public sealed class FeedbackProcessingException(ApiError error) : Exception(error.Message)
{
    public ApiError Error { get; } = error;
}

/// <summary>
/// 扫描反馈样本存储（Phase 4B-D2）。
/// 幂等：sampleId 为 Idempotency Key，已存在返回 AlreadyStored，绝不重复落盘。
/// </summary>
public interface IScannerFeedbackService
{
    Task<ScannerFeedbackResult> StoreAsync(
        ScannerFeedbackMetadata metadata,
        Stream file,
        CancellationToken cancellationToken);
}