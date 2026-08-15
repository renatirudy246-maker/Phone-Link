using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace PhoneLink.Core.Security;

public interface ITlsCertificateProvider
{
    X509Certificate2 GetOrCreateCertificate();
}

public static class CertificateFingerprint
{
    /// <summary>
    /// 协议级 canonical 表示：SHA-256，大写十六进制，冒号分隔（AA:BB:CC:...）。
    /// 协议、QR payload、SQLite、Android 钉扎统一使用该格式。
    /// </summary>
    public static string ComputeSha256Hex(X509Certificate2 certificate)
    {
        return Convert.ToHexString(SHA256.HashData(certificate.RawData))
            .Chunk(2)
            .Select(pair => new string(pair))
            .Aggregate((left, right) => left + ":" + right);
    }

    public static string ComputeSha256Base64(X509Certificate2 certificate)
    {
        return Convert.ToBase64String(SHA256.HashData(certificate.RawData));
    }
}