namespace PhoneLink.Core.Auth;

public sealed record TokenValidationResult(
    bool IsValid,
    string? DeviceId = null,
    string? DisplayName = null,
    string? Platform = null,
    string? ErrorCode = null);

public interface ITokenValidator
{
    Task<TokenValidationResult> ValidateAsync(string? bearerToken, CancellationToken cancellationToken);
}