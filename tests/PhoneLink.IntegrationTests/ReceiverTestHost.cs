using System.Net;
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using Microsoft.Extensions.Logging.Abstractions;
using PhoneLink.Core.Auth;
using PhoneLink.Core.Identity;
using PhoneLink.Core.Pairing;
using PhoneLink.Core.Security;
using PhoneLink.Core.Transfers;
using PhoneLink.Infrastructure.Auth;
using PhoneLink.Infrastructure.Identity;
using PhoneLink.Infrastructure.Pairing;
using PhoneLink.Infrastructure.Paths;
using PhoneLink.Infrastructure.Storage;
using PhoneLink.Infrastructure.Transfers;
using PhoneLink.Transport.Hosting;

namespace PhoneLink.IntegrationTests;

/// <summary>
/// 进程内真实 Receiver：真实 Kestrel HTTPS + 真实 SQLite + 真实文件管道。
/// 认证走真实配对流程：创建 PairingSession → POST /v1/pair 换取 Device Token。
/// </summary>
public sealed class ReceiverTestHost : IAsyncDisposable
{
    private readonly KestrelReceiverHost _host;
    private readonly string _baseDir;
    private readonly bool _deleteOnDispose;

    public AppPaths Paths { get; }
    public string BaseUrl { get; }
    public string Token { get; private set; } = string.Empty;
    public string DesktopDeviceId { get; }
    public string DesktopCertificateFingerprint { get; }
    public HttpClient Client { get; }
    public TransferEventBus Bus { get; }
    public ITransferRepository Repository { get; }
    public IPairingSessionService PairingSessionService { get; }
    public IPairedDeviceRepository DeviceRepository { get; }
    public IDeviceIdentityProvider Identity { get; }
    public X509Certificate2 Certificate { get; }
    public IReceiverHost Receiver => _host;

    private ReceiverTestHost(
        KestrelReceiverHost host,
        AppPaths paths,
        string baseUrl,
        HttpClient client,
        TransferEventBus bus,
        ITransferRepository repository,
        IPairingSessionService pairingSessionService,
        IPairedDeviceRepository deviceRepository,
        IDeviceIdentityProvider identity,
        X509Certificate2 certificate,
        string desktopDeviceId,
        string desktopCertificateFingerprint,
        string baseDir,
        bool deleteOnDispose)
    {
        _host = host;
        Paths = paths;
        BaseUrl = baseUrl;
        Client = client;
        Bus = bus;
        Repository = repository;
        PairingSessionService = pairingSessionService;
        DeviceRepository = deviceRepository;
        Identity = identity;
        Certificate = certificate;
        DesktopDeviceId = desktopDeviceId;
        DesktopCertificateFingerprint = desktopCertificateFingerprint;
        _baseDir = baseDir;
        _deleteOnDispose = deleteOnDispose;
    }

    public static Task<ReceiverTestHost> StartAsync()
        => StartAsyncWithBaseDir(Path.Combine(Path.GetTempPath(), $"phonelink-test-{Guid.NewGuid():N}"), deleteOnDispose: true);

    public static Task<ReceiverTestHost> StartAsyncWithBaseDir(string baseDir)
        => StartAsyncWithBaseDir(baseDir, deleteOnDispose: false);

    private static async Task<ReceiverTestHost> StartAsyncWithBaseDir(string baseDir, bool deleteOnDispose)
    {
        var paths = new AppPaths(baseDir);

        var db = new PhoneLinkDb(paths);
        var bus = new TransferEventBus();
        var repository = new TransferRepository(db);
        var fileStore = new TransferFileStore(paths);
        var service = new TransferService(
            repository, fileStore, bus, NullLogger<TransferService>.Instance);
        var identity = new SqliteDeviceIdentityProvider(db);
        var deviceRepository = new PairedDeviceRepository(db, NullLogger<PairedDeviceRepository>.Instance);
        var certificate = new TestCertificateProvider();
        var port = GetFreePort();
        var options = new ReceiverOptions { Port = port };
        var pairingSessionService = new PairingSessionService(
            db, identity, certificate, "127.0.0.1", port, NullLogger<PairingSessionService>.Instance);
        var validator = new PairedDeviceTokenValidator(db, deviceRepository);

        var host = new KestrelReceiverHost(
            options,
            identity,
            validator,
            service,
            NullLoggerFactory.Instance,
            certificate,
            pairingSessionService,
            deviceRepository);

        await host.StartAsync(CancellationToken.None);

        var handler = new HttpClientHandler
        {
            ServerCertificateCustomValidationCallback = (_, _, _, _) => true,
        };
        var client = new HttpClient(handler)
        {
            BaseAddress = new Uri($"https://127.0.0.1:{port}"),
            Timeout = TimeSpan.FromSeconds(60),
        };

        var identityInfo = await identity.GetIdentityAsync(CancellationToken.None);
        var desktopCert = certificate.GetOrCreateCertificate();
        return new ReceiverTestHost(
            host, paths, client.BaseAddress.ToString(), client, bus, repository,
            pairingSessionService, deviceRepository, identity, desktopCert,
            identityInfo.DeviceId,
            CertificateFingerprint.ComputeSha256Hex(desktopCert),
            baseDir, deleteOnDispose);
    }

    /// <summary>
    /// 走真实 /v1/pair 流程：创建 session → 解码 QR payload → 交换 Device Token。
    /// </summary>
    public async Task<string> PairAsync(
        string? mobileDeviceId = null,
        string mobileDeviceName = "Test Phone",
        string platform = "android",
        int? protocolVersion = null)
    {
        var created = await PairingSessionService.CreateAsync(CancellationToken.None);
        var payload = PairingQrCodec.Decode(created.QrPayload);

        using var request = new HttpRequestMessage(HttpMethod.Post, "/v1/pair")
        {
            Content = JsonContent.Create(new
            {
                oneTimeToken = payload.OneTimeToken,
                mobileDeviceId = mobileDeviceId ?? $"mobile-{Guid.NewGuid():N}",
                mobileDeviceName,
                platform,
                protocolVersion,
            }),
        };

        var response = await Client.SendAsync(request);
        Assert.True(response.IsSuccessStatusCode, $"Pair failed: {(int)response.StatusCode} {await response.Content.ReadAsStringAsync()}");
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Token = body.GetProperty("deviceToken").GetString()!;
        Assert.Equal(DesktopDeviceId, body.GetProperty("desktopDeviceId").GetString());
        return Token;
    }

    public async ValueTask DisposeAsync()
    {
        try
        {
            await _host.StopAsync(CancellationToken.None);
        }
        finally
        {
            Client.Dispose();
            if (_deleteOnDispose)
            {
                TryDeleteDir(_baseDir);
            }
        }
    }

    private static void TryDeleteDir(string path)
    {
        try
        {
            Directory.Delete(path, recursive: true);
        }
        catch (IOException)
        {
            // 清理尽力而为（SQLite 连接池可能短暂占用文件）。
        }
    }

    public async Task<HttpResponseMessage> UploadAsync(
        byte[] fileBytes,
        string mimeType,
        string? sha256 = null,
        string? transferId = null,
        string? fileName = null,
        string tokenOverride = "__use_default__")
    {
        var id = transferId ?? $"t-{Guid.NewGuid():N}";
        var metadata = new
        {
            transferId = id,
            senderDeviceId = "mobile-test-1",
            originalFileName = fileName ?? $"{id}.jpg",
            mimeType,
            fileSize = fileBytes.LongLength,
            width = 640,
            height = 480,
            sha256 = sha256 ?? TestImages.Sha256Hex(fileBytes),
            capturedAt = DateTimeOffset.UtcNow,
            sentAt = DateTimeOffset.UtcNow,
            purpose = "Question",
        };

        using var content = new MultipartFormDataContent();
        var metadataJson = JsonSerializer.Serialize(metadata);
        content.Add(new StringContent(metadataJson), "metadata");
        content.Add(new ByteArrayContent(fileBytes), "file", "photo.jpg");

        var request = new HttpRequestMessage(HttpMethod.Post, "/v1/transfers")
        {
            Content = content,
        };
        var token = tokenOverride == "__use_default__" ? Token : tokenOverride;
        if (token is not null)
        {
            request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", token);
        }

        return await Client.SendAsync(request);
    }

    private static int GetFreePort()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        return port;
    }

    public sealed class TestCertificateProvider : ITlsCertificateProvider
    {
        private X509Certificate2? _certificate;

        public X509Certificate2 GetOrCreateCertificate()
        {
            if (_certificate is not null)
            {
                return _certificate;
            }

            using var rsa = RSA.Create(2048);
            var request = new CertificateRequest(
                "CN=PhoneLink-Test",
                rsa,
                HashAlgorithmName.SHA256,
                RSASignaturePadding.Pkcs1);
            request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
            using var created = request.CreateSelfSigned(
                DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(30));
            _certificate = new X509Certificate2(
                created.Export(X509ContentType.Pfx),
                (string?)null,
                X509KeyStorageFlags.Exportable);
            return _certificate;
        }
    }
}