using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using PhoneLink.Core.Security;

namespace PhoneLink.Infrastructure.Crypto;

/// <summary>
/// Desktop TLS 身份：首次运行生成自签证书并持久化到当前用户证书库（DPAPI 保护）。
/// 后续重启继续使用同一证书（Android fingerprint pinning 的前提）。
/// storeName/location 可注入以便测试隔离（默认当前用户 My 库）。
/// </summary>
public sealed class CertificateStore : ITlsCertificateProvider
{
    private const string SubjectName = "PhoneLink-Desktop";
    private readonly string _storeName;
    private readonly StoreLocation _location;

    public CertificateStore()
        : this("My", StoreLocation.CurrentUser)
    {
    }

    /// <summary>storeName 可注入以便测试隔离（默认当前用户 My 库）。</summary>
    public CertificateStore(string storeName, StoreLocation location)
    {
        _storeName = storeName;
        _location = location;
    }

    public X509Certificate2 GetOrCreateCertificate()
    {
        using var store = new X509Store(_storeName, _location);
        store.Open(OpenFlags.ReadWrite);

        var existing = store.Certificates
            .Find(X509FindType.FindBySubjectName, SubjectName, validOnly: false)
            .OfType<X509Certificate2>()
            .FirstOrDefault(c => c.HasPrivateKey);

        if (existing is not null)
        {
            return existing;
        }

        var created = CreateSelfSigned();

        using var persisted = X509CertificateLoader.LoadPkcs12(
            created.Export(X509ContentType.Pfx),
            (string?)null,
            X509KeyStorageFlags.Exportable | X509KeyStorageFlags.PersistKeySet);

        store.Add(persisted);
        return new X509Certificate2(persisted);
    }

    private static X509Certificate2 CreateSelfSigned()
    {
        using var rsa = RSA.Create(2048);
        var request = new CertificateRequest(
            $"CN={SubjectName}",
            rsa,
            HashAlgorithmName.SHA256,
            RSASignaturePadding.Pkcs1);

        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(
            certificateAuthority: false, hasPathLengthConstraint: false, pathLengthConstraint: 0, critical: true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, critical: true));

        var sanBuilder = new SubjectAlternativeNameBuilder();
        sanBuilder.AddDnsName("localhost");
        sanBuilder.AddIpAddress(IPAddress.Loopback);
        request.CertificateExtensions.Add(sanBuilder.Build());

        return request.CreateSelfSigned(
            DateTimeOffset.UtcNow.AddDays(-1),
            DateTimeOffset.UtcNow.AddYears(5));
    }
}