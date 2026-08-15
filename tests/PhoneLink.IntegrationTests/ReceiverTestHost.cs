using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using Microsoft.Extensions.Logging.Abstractions;
using PhoneLink.Core.Auth;
using PhoneLink.Core.Identity;
using PhoneLink.Core.Security;
using PhoneLink.Core.Transfers;
using PhoneLink.Infrastructure.Auth;
using PhoneLink.Infrastructure.Identity;
using PhoneLink.Infrastructure.Paths;
using PhoneLink.Infrastructure.Storage;
using PhoneLink.Infrastructure.Transfers;
using PhoneLink.Transport.Hosting;

namespace PhoneLink.IntegrationTests;

/// <summary>
/// 进程内真实 Receiver：真实 Kestrel HTTPS + 真实 SQLite + 真实文件管道。
/// </summary>
public sealed class ReceiverTestHost : IAsyncDisposable
{
    private readonly KestrelReceiverHost _host;
    private readonly string _baseDir;
    private readonly bool _deleteOnDispose;

    public AppPaths Paths { get; }
    public string BaseUrl { get; }
    public string Token { get; }
    public HttpClient Client { get; }
    public TransferEventBus Bus { get; }
    public ITransferRepository Repository { get; }

    private ReceiverTestHost(
        KestrelReceiverHost host,
        AppPaths paths,
        string baseUrl,
        string token,
        HttpClient client,
        TransferEventBus bus,
        ITransferRepository repository,
        string baseDir,
        bool deleteOnDispose)
    {
        _host = host;
        Paths = paths;
        BaseUrl = baseUrl;
        Token = token;
        Client = client;
        Bus = bus;
        Repository = repository;
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
        var tokenStore = new DevTokenStore(paths);
        var validator = new DevTokenValidator(tokenStore);
        var identity = new SqliteDeviceIdentityProvider(db);
        var certificate = new TestCertificateProvider();
        var port = GetFreePort();
        var options = new ReceiverOptions { Port = port };

        var host = new KestrelReceiverHost(
            options,
            identity,
            validator,
            service,
            NullLoggerFactory.Instance,
            certificate);

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

        return new ReceiverTestHost(host, paths, client.BaseAddress.ToString(), tokenStore.Token, client, bus, repository, baseDir, deleteOnDispose);
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
        var metadataJson = System.Text.Json.JsonSerializer.Serialize(metadata);
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

    private sealed class TestCertificateProvider : ITlsCertificateProvider
    {
        public X509Certificate2 GetOrCreateCertificate()
        {
            using var rsa = RSA.Create(2048);
            var request = new CertificateRequest(
                "CN=PhoneLink-Test",
                rsa,
                HashAlgorithmName.SHA256,
                RSASignaturePadding.Pkcs1);
            request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
            using var created = request.CreateSelfSigned(
                DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(30));
            return new X509Certificate2(
                created.Export(X509ContentType.Pfx),
                (string?)null,
                X509KeyStorageFlags.Exportable);
        }
    }
}