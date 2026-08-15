namespace PhoneLink.Core.Models;

public sealed record PairedDevice(
    string DeviceId,
    string DisplayName,
    string Platform,
    string AuthTokenReference,
    string CertificateFingerprint,
    DateTimeOffset? LastSeenAt,
    string? LastKnownEndpoint,
    bool IsTrusted);