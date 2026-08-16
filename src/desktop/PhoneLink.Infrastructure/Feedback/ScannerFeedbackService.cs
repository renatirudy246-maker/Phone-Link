using System.Security.Cryptography;
using Microsoft.Extensions.Logging;
using PhoneLink.Core;
using PhoneLink.Core.Errors;
using PhoneLink.Core.Feedback;
using PhoneLink.Infrastructure.Paths;

namespace PhoneLink.Infrastructure.Feedback;

/// <summary>
/// 扫描反馈样本落盘（Phase 4B-D2）。
/// 布局：&lt;ScannerFeedbackDir&gt;\yyyy-MM\&lt;sampleId&gt;\{source.jpg, metadata.json}
/// 管道：tmp 目录写入 → 大小上限 → SHA-256 校验 → JPEG 文件头校验 → 原子 rename。
/// 幂等：sampleId 为 Idempotency Key，目录已存在即 AlreadyStored，绝不重复落盘。
/// 文件名/目录名完全由服务端生成（sampleId 已通过安全字符校验），绝不使用客户端提供的路径。
/// </summary>
public sealed class ScannerFeedbackService : IScannerFeedbackService
{
    private const int BufferSize = 64 * 1024;

    private readonly AppPaths _paths;
    private readonly ILogger<ScannerFeedbackService> _logger;

    public ScannerFeedbackService(AppPaths paths, ILogger<ScannerFeedbackService> logger)
    {
        _paths = paths;
        _logger = logger;
    }

    public async Task<ScannerFeedbackResult> StoreAsync(
        ScannerFeedbackMetadata metadata,
        Stream file,
        CancellationToken cancellationToken)
    {
        if (!IsSafeSampleId(metadata.SampleId))
        {
            throw new FeedbackProcessingException(new ApiError(
                ErrorCodes.FeedbackInvalid, "Invalid sample id.", Retryable: false));
        }

        var monthDir = Path.Combine(_paths.ScannerFeedbackDir, DateTimeOffset.UtcNow.ToString("yyyy-MM"));
        Directory.CreateDirectory(monthDir);

        var finalDir = Path.Combine(monthDir, metadata.SampleId);
        if (Directory.Exists(finalDir))
        {
            _logger.LogInformation("Scanner feedback {SampleId} already stored (idempotent replay)", metadata.SampleId);
            return new ScannerFeedbackResult(AlreadyStored: true, Error: null);
        }

        var tmpDir = Path.Combine(monthDir, $"{metadata.SampleId}.{Guid.NewGuid():N}.tmp");
        Directory.CreateDirectory(tmpDir);

        try
        {
            var sourcePath = Path.Combine(tmpDir, "source.jpg");
            var actualSha256 = await WriteSourceWithChecksAsync(file, sourcePath, cancellationToken);

            if (!string.Equals(actualSha256, metadata.Source.Sha256, StringComparison.OrdinalIgnoreCase))
            {
                throw new FeedbackProcessingException(new ApiError(
                    ErrorCodes.FeedbackHashMismatch, "File integrity check failed.", Retryable: false));
            }

            if (!IsJpegHeader(sourcePath))
            {
                throw new FeedbackProcessingException(new ApiError(
                    ErrorCodes.FeedbackInvalid, "File is not a JPEG image.", Retryable: false));
            }

            var metadataJson = ScannerFeedbackMetadataJson.Serialize(metadata);
            await File.WriteAllTextAsync(Path.Combine(tmpDir, "metadata.json"), metadataJson, cancellationToken);

            Directory.Move(tmpDir, finalDir);

            _logger.LogInformation(
                "Stored scanner feedback {SampleId} ({Reason}, {Bytes} bytes)",
                metadata.SampleId, metadata.Reason, actualSha256.Length);

            return new ScannerFeedbackResult(AlreadyStored: false, Error: null);
        }
        catch (FeedbackProcessingException)
        {
            TryDeleteDir(tmpDir);
            throw;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            TryDeleteDir(tmpDir);
            throw new FeedbackProcessingException(new ApiError(
                ErrorCodes.DiskWriteFailed, "Failed to write scanner feedback to disk.", Retryable: true));
        }
    }

    private static async Task<string> WriteSourceWithChecksAsync(
        Stream source,
        string sourcePath,
        CancellationToken cancellationToken)
    {
        using var hasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        long size = 0;

        await using (var file = new FileStream(
                         sourcePath,
                         FileMode.CreateNew,
                         FileAccess.Write,
                         FileShare.None,
                         BufferSize,
                         FileOptions.Asynchronous))
        {
            var buffer = new byte[BufferSize];
            int read;
            while ((read = await source.ReadAsync(buffer, cancellationToken)) > 0)
            {
                size += read;
                if (size > AppInfo.MaxImageSizeBytes)
                {
                    throw new FeedbackProcessingException(new ApiError(
                        ErrorCodes.FeedbackTooLarge, "File exceeds size limit.", Retryable: false));
                }

                hasher.AppendData(buffer, 0, read);
                await file.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
            }
        }

        return Convert.ToHexString(hasher.GetHashAndReset());
    }

    private static bool IsJpegHeader(string path)
    {
        using var file = File.OpenRead(path);
        Span<byte> header = stackalloc byte[3];
        int read = file.Read(header);
        return read >= 3 && header[0] == 0xFF && header[1] == 0xD8 && header[2] == 0xFF;
    }

    private static bool IsSafeSampleId(string sampleId)
    {
        if (string.IsNullOrEmpty(sampleId) || sampleId.Length > 128)
        {
            return false;
        }

        foreach (var c in sampleId)
        {
            if (!(char.IsAsciiLetterOrDigit(c) || c is '_' or '-'))
            {
                return false;
            }
        }

        return true;
    }

    private static void TryDeleteDir(string path)
    {
        try
        {
            if (Directory.Exists(path))
            {
                Directory.Delete(path, recursive: true);
            }
        }
        catch
        {
            // 尽力清理，失败不掩盖原始错误。
        }
    }
}