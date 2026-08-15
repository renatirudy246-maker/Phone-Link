using PhoneLink.Core;
using PhoneLink.Core.Errors;
using PhoneLink.Core.Models;

namespace PhoneLink.Core.Tests;

public class AppInfoTests
{
    [Fact]
    public void ProtocolVersion_IsOne()
    {
        Assert.Equal(1, AppInfo.ProtocolVersion);
    }

    [Fact]
    public void MaxImageSize_Is25Mb()
    {
        Assert.Equal(25 * 1024 * 1024, AppInfo.MaxImageSizeBytes);
    }

    [Fact]
    public void SupportedMimeTypes_IncludeJpegPngWebp()
    {
        Assert.Contains("image/jpeg", AppInfo.SupportedImageMimeTypes);
        Assert.Contains("image/png", AppInfo.SupportedImageMimeTypes);
        Assert.Contains("image/webp", AppInfo.SupportedImageMimeTypes);
    }
}

public class ErrorCodeTests
{
    [Theory]
    [InlineData("PAIR_TOKEN_INVALID")]
    [InlineData("PAIR_TOKEN_EXPIRED")]
    [InlineData("PAIR_ALREADY_USED")]
    [InlineData("AUTH_INVALID")]
    [InlineData("DEVICE_REVOKED")]
    [InlineData("UNSUPPORTED_PROTOCOL")]
    [InlineData("FILE_TOO_LARGE")]
    [InlineData("UNSUPPORTED_MEDIA_TYPE")]
    [InlineData("TRANSFER_HASH_MISMATCH")]
    [InlineData("DISK_WRITE_FAILED")]
    [InlineData("NETWORK_TIMEOUT")]
    [InlineData("DESKTOP_OFFLINE")]
    [InlineData("AI_AUTH_FAILED")]
    [InlineData("AI_TIMEOUT")]
    [InlineData("AI_PROVIDER_ERROR")]
    public void ErrorCodes_AllDefined(string expected)
    {
        var actual = new[]
        {
            ErrorCodes.PairTokenInvalid,
            ErrorCodes.PairTokenExpired,
            ErrorCodes.PairAlreadyUsed,
            ErrorCodes.AuthInvalid,
            ErrorCodes.DeviceRevoked,
            ErrorCodes.UnsupportedProtocol,
            ErrorCodes.FileTooLarge,
            ErrorCodes.UnsupportedMediaType,
            ErrorCodes.TransferHashMismatch,
            ErrorCodes.DiskWriteFailed,
            ErrorCodes.NetworkTimeout,
            ErrorCodes.DesktopOffline,
            ErrorCodes.AiAuthFailed,
            ErrorCodes.AiTimeout,
            ErrorCodes.AiProviderError,
        };

        Assert.Contains(expected, actual);
    }

    [Fact]
    public void ApiError_CarriesRetryableFlag()
    {
        var error = new ApiError(ErrorCodes.TransferHashMismatch, "File integrity check failed.", true);
        Assert.True(error.Retryable);
        Assert.Equal(ErrorCodes.TransferHashMismatch, error.Code);
    }
}

public class TransferManifestTests
{
    [Fact]
    public void TransferManifest_DefaultsToQuestionPurpose()
    {
        var manifest = new TransferManifest(
            TransferId: "t-1",
            SenderDeviceId: "mobile-1",
            OriginalFileName: "question.jpg",
            MimeType: "image/jpeg",
            FileSize: 1024,
            Width: 3000,
            Height: 4000,
            Sha256: "abc",
            CapturedAt: DateTimeOffset.UtcNow,
            SentAt: DateTimeOffset.UtcNow,
            Purpose: TransferPurpose.Question);

        Assert.Equal(TransferPurpose.Question, manifest.Purpose);
        Assert.Equal("t-1", manifest.TransferId);
    }

    [Fact]
    public void TransferRecord_CompletedState()
    {
        var record = new TransferRecord(
            TransferId: "t-1",
            SenderDeviceId: "mobile-1",
            OriginalFileName: "question.jpg",
            MimeType: "image/jpeg",
            FileSize: 1024,
            Width: 3000,
            Height: 4000,
            Sha256: "abc",
            CapturedAt: DateTimeOffset.UtcNow,
            SentAt: DateTimeOffset.UtcNow,
            Purpose: TransferPurpose.Question,
            LocalFilePath: @"C:\inbox\t-1.jpg",
            ThumbnailPath: null,
            ReceivedAt: DateTimeOffset.UtcNow,
            Status: TransferStatus.Completed,
            ErrorCode: null);

        Assert.Equal(TransferStatus.Completed, record.Status);
        Assert.Null(record.ErrorCode);
        Assert.Null(record.ThumbnailPath);
    }
}