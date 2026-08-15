using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using PhoneLink.Core.Security;

namespace PhoneLink.Core.Tests;

public class CertificateFingerprintTests
{
    [Fact]
    public void ComputeSha256Base64_IsStableForSameCertificate()
    {
        using var certificate = CreateSelfSigned();

        var first = CertificateFingerprint.ComputeSha256Base64(certificate);
        var second = CertificateFingerprint.ComputeSha256Base64(certificate);

        Assert.Equal(first, second);
        Assert.Equal(44, first.Length);
        Assert.True(CertificateFingerprint.ComputeSha256Base64(certificate) is not null);
    }

    internal static X509Certificate2 CreateSelfSigned()
    {
        using var rsa = RSA.Create(2048);
        var request = new CertificateRequest(
            "CN=PhoneLink-Test",
            rsa,
            HashAlgorithmName.SHA256,
            RSASignaturePadding.Pkcs1);
        return request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(30));
    }
}