namespace PhoneLink.Core.Models;

public sealed record PairingSession(
    string SessionId,
    string OneTimeToken,
    DateTimeOffset ExpiresAt,
    string DesktopDeviceId,
    string DesktopDisplayName,
    string Endpoint,
    string CertificateFingerprint,
    bool Consumed);