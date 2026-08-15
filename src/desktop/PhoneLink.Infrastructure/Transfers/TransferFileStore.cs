using System.Security.Cryptography;
using PhoneLink.Core;
using PhoneLink.Core.Errors;
using PhoneLink.Core.Transfers;
using PhoneLink.Infrastructure.Paths;

namespace PhoneLink.Infrastructure.Transfers;

/// <summary>
/// 文件落盘管道：temp 写入 → 大小上限 → MIME 文件头校验 → SHA-256 校验 → 原子移动到 inbox。
/// 本地文件名完全由服务端生成（&lt;transferId&gt;.&lt;ext&gt;），绝不使用客户端提供的路径。
/// </summary>
public sealed class TransferFileStore : ITransferFileStore
{
    private const int BufferSize = 64 * 1024;

    private readonly AppPaths _paths;

    public TransferFileStore(AppPaths paths)
    {
        _paths = paths;
    }

    public async Task<FileWriteResult> WriteAsync(
        Stream source,
        string transferId,
        string declaredSha256,
        string declaredMimeType,
        long maxBytes,
        CancellationToken cancellationToken)
    {
        if (!AppInfo.SupportedImageMimeTypes.Contains(declaredMimeType, StringComparer.OrdinalIgnoreCase))
        {
            throw new TransferProcessingException(new ApiError(
                ErrorCodes.UnsupportedMediaType, "Unsupported media type.", Retryable: false));
        }

        var tempPath = Path.Combine(_paths.TempDir, $"{Guid.NewGuid():N}.tmp");
        long size = 0;
        string actualSha256;

        try
        {
            using var hasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);

            await using (var file = new FileStream(
                             tempPath,
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
                    if (size > maxBytes)
                    {
                        throw new TransferProcessingException(new ApiError(
                            ErrorCodes.FileTooLarge, "File exceeds size limit.", Retryable: false));
                    }

                    hasher.AppendData(buffer, 0, read);
                    await file.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
                }
            }

            actualSha256 = Convert.ToHexString(hasher.GetHashAndReset());

            var detectedMime = SniffMimeType(tempPath);
            if (!string.Equals(detectedMime, declaredMimeType, StringComparison.OrdinalIgnoreCase))
            {
                throw new TransferProcessingException(new ApiError(
                    ErrorCodes.UnsupportedMediaType, "File content does not match declared MIME type.", Retryable: false));
            }

            if (!string.Equals(actualSha256, declaredSha256, StringComparison.OrdinalIgnoreCase))
            {
                throw new TransferProcessingException(new ApiError(
                    ErrorCodes.TransferHashMismatch, "File integrity check failed.", Retryable: true));
            }

            var extension = MimeToExtension(detectedMime);
            var destinationDir = Path.Combine(_paths.InboxDir, DateTimeOffset.UtcNow.ToString("yyyy-MM-dd"));
            Directory.CreateDirectory(destinationDir);
            var destinationPath = Path.Combine(destinationDir, $"{transferId}.{extension}");

            File.Move(tempPath, destinationPath, overwrite: false);

            return new FileWriteResult(destinationPath, actualSha256, size);
        }
        catch (TransferProcessingException)
        {
            TryDelete(tempPath);
            throw;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            TryDelete(tempPath);
            throw new TransferProcessingException(new ApiError(
                ErrorCodes.DiskWriteFailed, "Failed to write file to disk.", Retryable: true));
        }
    }

    private static string SniffMimeType(string path)
    {
        using var file = File.OpenRead(path);
        Span<byte> header = stackalloc byte[12];
        int read = 0;
        while (read < header.Length)
        {
            int n = file.Read(header[read..]);
            if (n == 0)
            {
                break;
            }

            read += n;
        }

        if (read >= 3 && header[0] == 0xFF && header[1] == 0xD8 && header[2] == 0xFF)
        {
            return "image/jpeg";
        }

        if (read >= 8
            && header[0] == 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47
            && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A)
        {
            return "image/png";
        }

        if (read >= 12
            && header[0] == (byte)'R' && header[1] == (byte)'I' && header[2] == (byte)'F' && header[3] == (byte)'F'
            && header[8] == (byte)'W' && header[9] == (byte)'E' && header[10] == (byte)'B' && header[11] == (byte)'P')
        {
            return "image/webp";
        }

        return "application/octet-stream";
    }

    private static string MimeToExtension(string mimeType) => mimeType switch
    {
        "image/jpeg" => "jpg",
        "image/png" => "png",
        "image/webp" => "webp",
        _ => throw new ArgumentOutOfRangeException(nameof(mimeType)),
    };

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
            // 尽力清理，失败不掩盖原始错误。
        }
    }
}