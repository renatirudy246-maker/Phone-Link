using PhoneLink.Core.Errors;

namespace PhoneLink.Transport.Http;

public static class ApiErrorMapper
{
    public static int StatusCodeFor(string code) => code switch
    {
        ErrorCodes.AuthInvalid or ErrorCodes.PairTokenInvalid => StatusCodes.Status401Unauthorized,
        ErrorCodes.DeviceRevoked or ErrorCodes.PairTokenExpired or ErrorCodes.PairAlreadyUsed => StatusCodes.Status403Forbidden,
        ErrorCodes.FileTooLarge => StatusCodes.Status413PayloadTooLarge,
        ErrorCodes.UnsupportedMediaType => StatusCodes.Status415UnsupportedMediaType,
        ErrorCodes.TransferHashMismatch => StatusCodes.Status422UnprocessableEntity,
        ErrorCodes.DiskWriteFailed or ErrorCodes.AiProviderError => StatusCodes.Status500InternalServerError,
        ErrorCodes.NotFound => StatusCodes.Status404NotFound,
        _ => StatusCodes.Status400BadRequest,
    };

    public static IResult ToResult(ApiError error)
        => Results.Json(
            new { code = error.Code, message = error.Message, retryable = error.Retryable },
            statusCode: StatusCodeFor(error.Code));

    public static IResult NotFound()
        => ToResult(new ApiError(ErrorCodes.NotFound, "Resource not found.", Retryable: false));

    public static IResult InvalidRequest(string message = "Malformed request.")
        => ToResult(new ApiError(ErrorCodes.InvalidRequest, message, Retryable: false));
}