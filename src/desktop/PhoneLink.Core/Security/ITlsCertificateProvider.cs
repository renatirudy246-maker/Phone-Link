using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace PhoneLink.Core.Security;

public interface ITlsCertificateProvider
{
    X509Certificate2 GetOrCreateCertificate();
}

public static class CertificateFingerprint
{
    public static string ComputeSha256Base64(X509Certificate2 certificate)
    {
        return Convert.ToBase64String(SHA256.HashData(certificate.RawData));
    }
}