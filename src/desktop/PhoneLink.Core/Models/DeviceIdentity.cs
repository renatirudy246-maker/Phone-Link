namespace PhoneLink.Core.Models;

public sealed record DeviceIdentity(
    string DeviceId,
    string DisplayName,
    string Platform,
    DateTimeOffset CreatedAt,
    string PublicFingerprint);