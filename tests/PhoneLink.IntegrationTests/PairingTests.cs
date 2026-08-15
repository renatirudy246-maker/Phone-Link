using System.Security.Cryptography.X509Certificates;
using PhoneLink.Core;
using PhoneLink.Core.Pairing;
using PhoneLink.Core.Security;
using PhoneLink.Infrastructure.Crypto;

namespace PhoneLink.IntegrationTests;

public class QrCodecTests
{
    private static PairingQrPayload ValidPayload() => new(
        ProtocolVersion: 1,
        DesktopDeviceId: "desktop-test",
        DesktopDeviceName: "Jacob-PC",
        Host: "192.168.1.10",
        Port: 8484,
        OneTimeToken: "token-abc",
        CertificateFingerprint: "AA:BB:CC",
        ExpiresAt: DateTimeOffset.UtcNow.AddMinutes(3));

    [Fact]
    public void RoundTrip_EncodeDecode_PreservesAllFields()
    {
        var original = ValidPayload();
        var decoded = PairingQrCodec.Decode(PairingQrCodec.Encode(original));

        Assert.Equal(original, decoded);
    }

    [Fact]
    public void Decode_EmptyPayload_ThrowsFormatException()
    {
        Assert.Throws<FormatException>(() => PairingQrCodec.Decode(string.Empty));
        Assert.Throws<FormatException>(() => PairingQrCodec.Decode("   "));
    }

    [Fact]
    public void Decode_NotBase64Url_ThrowsFormatException()
    {
        Assert.Throws<FormatException>(() => PairingQrCodec.Decode("!!!not-base64!!!"));
    }

    [Fact]
    public void Decode_NotJson_ThrowsFormatException()
    {
        var garbage = Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes("just some text"));
        Assert.Throws<FormatException>(() => PairingQrCodec.Decode(garbage));
    }

    [Fact]
    public void Decode_MissingRequiredFields_ThrowsFormatException()
    {
        var payload = ValidPayload() with { OneTimeToken = "" };
        var encoded = PairingQrCodec.Encode(payload);
        Assert.Throws<FormatException>(() => PairingQrCodec.Decode(encoded));
    }

    [Fact]
    public void Decode_InvalidPort_ThrowsFormatException()
    {
        var payload = ValidPayload() with { Port = 99999 };
        var encoded = PairingQrCodec.Encode(payload);
        Assert.Throws<FormatException>(() => PairingQrCodec.Decode(encoded));
    }

    [Fact]
    public void Decode_TooLargePayload_ThrowsFormatException()
    {
        var huge = new string('A', 5000);
        Assert.Throws<FormatException>(() => PairingQrCodec.Decode(huge));
    }

    [Fact]
    public void Fingerprint_CanonicalFormat_IsUppercaseColonHex()
    {
        using var cert = CreateTestCertificate();
        var fingerprint = CertificateFingerprint.ComputeSha256Hex(cert);

        Assert.Matches("^([0-9A-F]{2}:){31}[0-9A-F]{2}$", fingerprint);
        Assert.Equal(95, fingerprint.Length);
    }

    internal static X509Certificate2 CreateTestCertificate()
    {
        using var rsa = System.Security.Cryptography.RSA.Create(2048);
        var request = new System.Security.Cryptography.X509Certificates.CertificateRequest(
            "CN=PhoneLink-Test",
            rsa,
            System.Security.Cryptography.HashAlgorithmName.SHA256,
            System.Security.Cryptography.RSASignaturePadding.Pkcs1);
        using var created = request.CreateSelfSigned(
            DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(30));
        return new X509Certificate2(
            created.Export(X509ContentType.Pfx),
            (string?)null,
            X509KeyStorageFlags.Exportable);
    }
}

public class CertificateStabilityTests
{
    private const string TestStoreName = "PhoneLinkTests";

    [Fact]
    public void CertificateStore_Restart_SameCertificateAndFingerprint()
    {
        try
        {
            var first = new CertificateStore(TestStoreName, StoreLocation.CurrentUser).GetOrCreateCertificate();
            var second = new CertificateStore(TestStoreName, StoreLocation.CurrentUser).GetOrCreateCertificate();

            Assert.Equal(first.Thumbprint, second.Thumbprint);
            Assert.Equal(
                CertificateFingerprint.ComputeSha256Hex(first),
                CertificateFingerprint.ComputeSha256Hex(second));
        }
        finally
        {
            using var store = new X509Store(TestStoreName, StoreLocation.CurrentUser);
            store.Open(OpenFlags.ReadWrite);
            var stale = store.Certificates.Find(X509FindType.FindBySubjectName, "PhoneLink-Desktop", false);
            foreach (var certificate in stale)
            {
                store.Remove(certificate);
            }
        }
    }
}

public class MdnsTxtTests
{
    [Fact]
    public void Build_ContainsOnlyNonSensitiveMetadata()
    {
        var txt = MdnsTxt.Build(1, "desktop-abc123", "Jacob-PC");

        Assert.Equal("1", txt["version"]);
        Assert.Equal("desktop-abc123", txt["deviceId"]);
        Assert.Equal("Jacob-PC", txt["name"]);

        // 禁止任何敏感字段
        Assert.DoesNotContain(txt.Keys, k => k.Contains("token", StringComparison.OrdinalIgnoreCase));
        Assert.DoesNotContain(txt.Keys, k => k.Contains("key", StringComparison.OrdinalIgnoreCase));
        Assert.DoesNotContain(txt.Keys, k => k.Contains("secret", StringComparison.OrdinalIgnoreCase));
        Assert.DoesNotContain(txt.Keys, k => k.Contains("fingerprint", StringComparison.OrdinalIgnoreCase));
        Assert.DoesNotContain(txt.Keys, k => k.Contains("auth", StringComparison.OrdinalIgnoreCase));
    }
}