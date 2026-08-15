namespace PhoneLink.Core.Errors;

public static class ErrorCodes
{
    public const string PairTokenInvalid = "PAIR_TOKEN_INVALID";
    public const string PairTokenExpired = "PAIR_TOKEN_EXPIRED";
    public const string PairAlreadyUsed = "PAIR_ALREADY_USED";
    public const string AuthInvalid = "AUTH_INVALID";
    public const string DeviceRevoked = "DEVICE_REVOKED";
    public const string UnsupportedProtocol = "UNSUPPORTED_PROTOCOL";
    public const string FileTooLarge = "FILE_TOO_LARGE";
    public const string UnsupportedMediaType = "UNSUPPORTED_MEDIA_TYPE";
    public const string TransferHashMismatch = "TRANSFER_HASH_MISMATCH";
    public const string DiskWriteFailed = "DISK_WRITE_FAILED";
    public const string InvalidRequest = "INVALID_REQUEST";
    public const string NotFound = "NOT_FOUND";
    public const string NetworkTimeout = "NETWORK_TIMEOUT";
    public const string DesktopOffline = "DESKTOP_OFFLINE";
    public const string ServicePaused = "SERVICE_PAUSED";
    public const string AiAuthFailed = "AI_AUTH_FAILED";
    public const string AiTimeout = "AI_TIMEOUT";
    public const string AiProviderError = "AI_PROVIDER_ERROR";
}

public sealed record ApiError(string Code, string Message, bool Retryable);