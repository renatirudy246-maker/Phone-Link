using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using PhoneLink.Core.Models;
using PhoneLink.Core.Pairing;
using PhoneLink.Infrastructure.Paths;
using PhoneLink.Infrastructure.Storage;
using PhoneLink.Infrastructure.Transfers;
using PhoneLink.Transport.Hosting;

namespace PhoneLink.IntegrationTests;

public class ReceiverHttpTests
{
    [Fact]
    public async Task Health_WithoutToken_ReturnsProtocolVersionAndStatusOnly()
    {
        await using var host = await ReceiverTestHost.StartAsync();

        var response = await host.Client.GetAsync("/v1/health");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal(1, body.GetProperty("protocolVersion").GetInt32());
        Assert.Equal("ok", body.GetProperty("status").GetString());
        Assert.False(body.TryGetProperty("deviceId", out _));
        Assert.False(body.TryGetProperty("deviceName", out _));
    }

    [Fact]
    public async Task Health_WithPairedDeviceToken_ReturnsDeviceInfo()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();

        using var request = new HttpRequestMessage(HttpMethod.Get, "/v1/health");
        request.Headers.Authorization = new("Bearer", host.Token);
        var response = await host.Client.SendAsync(request);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal(1, body.GetProperty("protocolVersion").GetInt32());
        Assert.StartsWith("desktop-", body.GetProperty("deviceId").GetString());
        Assert.Equal("ok", body.GetProperty("status").GetString());
    }

    [Fact]
    public async Task Health_WrongToken_Rejected()
    {
        await using var host = await ReceiverTestHost.StartAsync();

        using var request = new HttpRequestMessage(HttpMethod.Get, "/v1/health");
        request.Headers.Authorization = new("Bearer", "wrong-token");
        var response = await host.Client.SendAsync(request);
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task Pair_ValidSession_IssuesDeviceToken()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        var token = await host.PairAsync(mobileDeviceId: "mobile-pair-1");

        Assert.False(string.IsNullOrWhiteSpace(token));
        var devices = await host.DeviceRepository.ListAllAsync(CancellationToken.None);
        var device = Assert.Single(devices);
        Assert.Equal("mobile-pair-1", device.DeviceId);
        Assert.True(device.IsTrusted);
        Assert.Equal(host.DesktopCertificateFingerprint, device.CertificateFingerprint);
    }

    [Fact]
    public async Task Pair_InvalidToken_Rejected()
    {
        await using var host = await ReceiverTestHost.StartAsync();

        using var request = new HttpRequestMessage(HttpMethod.Post, "/v1/pair")
        {
            Content = JsonContent.Create(new
            {
                oneTimeToken = "not-a-real-token",
                mobileDeviceId = "mobile-x",
                mobileDeviceName = "X",
                platform = "android",
            }),
        };
        var response = await host.Client.SendAsync(request);
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        var error = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("PAIR_TOKEN_INVALID", error.GetProperty("code").GetString());
    }

    [Fact]
    public async Task Pair_ExpiredToken_Rejected()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        var created = await host.PairingSessionService.CreateAsync(CancellationToken.None);
        var payload = PairingQrCodec.Decode(created.QrPayload);

        // 过期后必须失败（SessionTtl 为 3 分钟，这里直接等过期不可行，改用已过期的会话注入不可行；
        // 因此通过把 session 的 expires_at 改到过去模拟）
        await using (var connection = host.Paths is not null ? OpenDb(host) : null!)
        {
            await using var command = connection!.CreateCommand();
            command.CommandText = "UPDATE pairing_sessions SET expires_at = $past WHERE session_id = $id;";
            command.Parameters.AddWithValue("$past", DateTimeOffset.UtcNow.AddMinutes(-5).ToString("O"));
            command.Parameters.AddWithValue("$id", created.Session.SessionId);
            await command.ExecuteNonQueryAsync();
        }

        using var request = new HttpRequestMessage(HttpMethod.Post, "/v1/pair")
        {
            Content = JsonContent.Create(new
            {
                oneTimeToken = payload.OneTimeToken,
                mobileDeviceId = "mobile-x",
                mobileDeviceName = "X",
                platform = "android",
            }),
        };
        var response = await host.Client.SendAsync(request);
        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        var error = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("PAIR_TOKEN_EXPIRED", error.GetProperty("code").GetString());
    }

    [Fact]
    public async Task Pair_ReusedToken_Rejected()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync(mobileDeviceId: "mobile-reuse-1");

        // 第二次使用同一 token（重放）
        var created = await host.PairingSessionService.CreateAsync(CancellationToken.None);
        var payload = PairingQrCodec.Decode(created.QrPayload);

        async Task<HttpResponseMessage> TryPair()
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, "/v1/pair")
            {
                Content = JsonContent.Create(new
                {
                    oneTimeToken = payload.OneTimeToken,
                    mobileDeviceId = "mobile-reuse-2",
                    mobileDeviceName = "X",
                    platform = "android",
                }),
            };
            return await host.Client.SendAsync(request);
        }

        var first = await TryPair();
        Assert.Equal(HttpStatusCode.OK, first.StatusCode);

        var second = await TryPair();
        Assert.Equal(HttpStatusCode.Forbidden, second.StatusCode);
        var error = await second.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("PAIR_ALREADY_USED", error.GetProperty("code").GetString());
    }

    [Fact]
    public async Task Pair_MalformedBody_Rejected()
    {
        await using var host = await ReceiverTestHost.StartAsync();

        using var request = new HttpRequestMessage(HttpMethod.Post, "/v1/pair")
        {
            Content = new StringContent("not-json", System.Text.Encoding.UTF8, "application/json"),
        };
        var response = await host.Client.SendAsync(request);
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var error = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("INVALID_REQUEST", error.GetProperty("code").GetString());
    }

    [Fact]
    public async Task Pair_UnsupportedProtocol_Rejected()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        var created = await host.PairingSessionService.CreateAsync(CancellationToken.None);
        var payload = PairingQrCodec.Decode(created.QrPayload);

        using var request = new HttpRequestMessage(HttpMethod.Post, "/v1/pair")
        {
            Content = JsonContent.Create(new
            {
                oneTimeToken = payload.OneTimeToken,
                mobileDeviceId = "mobile-x",
                mobileDeviceName = "X",
                platform = "android",
                protocolVersion = 99,
            }),
        };
        var response = await host.Client.SendAsync(request);
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var error = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("UNSUPPORTED_PROTOCOL", error.GetProperty("code").GetString());
    }

    [Fact]
    public async Task Auth_DevToken_NoLongerAccepted()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();

        // Phase 1 的 dev-token 明文文件 / 任意旧令牌都不再是有效认证
        var response = await host.UploadAsync(
            TestImages.TinyJpeg, "image/jpeg", tokenOverride: "legacy-dev-token-value");
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        var error = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("AUTH_INVALID", error.GetProperty("code").GetString());
    }

    [Fact]
    public async Task Auth_UnknownDeviceToken_Rejected()
    {
        await using var host = await ReceiverTestHost.StartAsync();

        var response = await host.UploadAsync(
            TestImages.TinyJpeg, "image/jpeg", tokenOverride: "some-random-device-token");
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task Revoke_PairedDevice_NextRequestRejectedImmediately()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        var deviceId = $"mobile-revoke-{Guid.NewGuid():N}";
        await host.PairAsync(mobileDeviceId: deviceId);

        var before = await host.UploadAsync(TestImages.TinyJpeg, "image/jpeg");
        Assert.Equal(HttpStatusCode.OK, before.StatusCode);

        await host.DeviceRepository.SetRevokedAsync(deviceId, revoked: true, CancellationToken.None);

        // transfer 上传 + health 认证端点都必须拒绝已撤销设备
        var after = await host.UploadAsync(TestImages.TinyJpeg, "image/jpeg");
        Assert.Equal(HttpStatusCode.Forbidden, after.StatusCode);
        var error = await after.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("DEVICE_REVOKED", error.GetProperty("code").GetString());

        using var healthRequest = new HttpRequestMessage(HttpMethod.Get, "/v1/health");
        healthRequest.Headers.Authorization = new("Bearer", host.Token);
        var health = await host.Client.SendAsync(healthRequest);
        Assert.Equal(HttpStatusCode.Forbidden, health.StatusCode);
        var healthError = await health.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("DEVICE_REVOKED", healthError.GetProperty("code").GetString());
    }

    [Fact]
    public async Task Storage_RawDeviceTokenNotPresentInDatabase()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        var token = await host.PairAsync(mobileDeviceId: "mobile-secret-1");

        var dbBytes = await File.ReadAllBytesAsync(host.Paths.DbPath);
        var dbText = System.Text.Encoding.UTF8.GetString(dbBytes);

        Assert.DoesNotContain(token, dbText);
        Assert.DoesNotContain("Bearer", dbText);
    }

    [Fact]
    public async Task Storage_DevicePersistsAfterRestart()
    {
        var baseDir = Path.Combine(Path.GetTempPath(), $"phonelink-pair-persist-{Guid.NewGuid():N}");
        try
        {
            string token;
            string deviceId;
            await using (var host = await ReceiverTestHost.StartAsyncWithBaseDir(baseDir))
            {
                deviceId = $"mobile-{Guid.NewGuid():N}";
                token = await host.PairAsync(mobileDeviceId: deviceId);
            }

            await using (var host = await ReceiverTestHost.StartAsyncWithBaseDir(baseDir))
            {
                var device = await host.DeviceRepository.GetByDeviceIdAsync(deviceId, CancellationToken.None);
                Assert.NotNull(device);
                Assert.True(device!.IsTrusted);

                using var request = new HttpRequestMessage(HttpMethod.Get, "/v1/health");
                request.Headers.Authorization = new("Bearer", token);
                var response = await host.Client.SendAsync(request);
                Assert.Equal(HttpStatusCode.OK, response.StatusCode);
            }
        }
        finally
        {
            try
            {
                Directory.Delete(baseDir, recursive: true);
            }
            catch (IOException)
            {
            }
        }
    }

    [Fact]
    public async Task EndpointChange_SameIdentityAndFingerprint_AuthenticationSucceeds()
    {
        // 模拟 PC DHCP/IP 变化：同一身份 + 同一证书 + 同一数据库，通过新 endpoint 认证成功
        var baseDir = Path.Combine(Path.GetTempPath(), $"phonelink-endpoint-{Guid.NewGuid():N}");
        try
        {
            var paths = new AppPaths(baseDir);
            var db = new PhoneLinkDb(paths);
            var identity = new PhoneLink.Infrastructure.Identity.SqliteDeviceIdentityProvider(db);
            var deviceRepository = new PhoneLink.Infrastructure.Pairing.PairedDeviceRepository(
                db, Microsoft.Extensions.Logging.Abstractions.NullLogger<PhoneLink.Infrastructure.Pairing.PairedDeviceRepository>.Instance);
            var certificate = new ReceiverTestHost.TestCertificateProvider();

            var (hostA, portA, clientA) = await StartEndpointAsync(db, identity, certificate, deviceRepository, baseDir);
            var (hostB, portB, clientB) = await StartEndpointAsync(db, identity, certificate, deviceRepository, baseDir);
            await using (hostA)
            await using (hostB)
            using (clientA)
            using (clientB)
            {
                // 通过 endpoint A 配对
                var pairingA = new PhoneLink.Infrastructure.Pairing.PairingSessionService(
                    db, identity, certificate, "127.0.0.1", portA,
                    Microsoft.Extensions.Logging.Abstractions.NullLogger<PhoneLink.Infrastructure.Pairing.PairingSessionService>.Instance);
                var created = await pairingA.CreateAsync(CancellationToken.None);
                var payload = PairingQrCodec.Decode(created.QrPayload);

                using var pairRequest = new HttpRequestMessage(HttpMethod.Post, "/v1/pair")
                {
                    Content = JsonContent.Create(new
                    {
                        oneTimeToken = payload.OneTimeToken,
                        mobileDeviceId = "mobile-endpoint-1",
                        mobileDeviceName = "X",
                        platform = "android",
                    }),
                };
                var pairResponse = await clientA.SendAsync(pairRequest);
                Assert.Equal(HttpStatusCode.OK, pairResponse.StatusCode);
                var pairBody = await pairResponse.Content.ReadFromJsonAsync<JsonElement>();
                var token = pairBody.GetProperty("deviceToken").GetString()!;

                // 旧 endpoint 失效（hostA 已停），通过新 endpoint B 认证（同一 DeviceId + 同一 fingerprint）
                await hostA.StopAsync(CancellationToken.None);

                using var healthRequest = new HttpRequestMessage(HttpMethod.Get, "/v1/health");
                healthRequest.Headers.Authorization = new("Bearer", token);
                var health = await clientB.SendAsync(healthRequest);
                Assert.Equal(HttpStatusCode.OK, health.StatusCode);
                var healthBody = await health.Content.ReadFromJsonAsync<JsonElement>();
                Assert.Equal(identity.GetIdentityAsync(CancellationToken.None).Result.DeviceId,
                    healthBody.GetProperty("deviceId").GetString());

                // 上传也成功
                using var content = new MultipartFormDataContent();
                content.Add(new StringContent(System.Text.Json.JsonSerializer.Serialize(new
                {
                    transferId = $"t-{Guid.NewGuid():N}",
                    senderDeviceId = "mobile-endpoint-1",
                    originalFileName = "x.jpg",
                    mimeType = "image/jpeg",
                    fileSize = TestImages.TinyJpeg.Length,
                    sha256 = TestImages.Sha256Hex(TestImages.TinyJpeg),
                })), "metadata");
                content.Add(new ByteArrayContent(TestImages.TinyJpeg), "file", "photo.jpg");
                using var uploadRequest = new HttpRequestMessage(HttpMethod.Post, "/v1/transfers")
                {
                    Content = content,
                };
                uploadRequest.Headers.Authorization = new("Bearer", token);
                var upload = await clientB.SendAsync(uploadRequest);
                Assert.Equal(HttpStatusCode.OK, upload.StatusCode);
            }
        }
        finally
        {
            try
            {
                Directory.Delete(baseDir, recursive: true);
            }
            catch (IOException)
            {
            }
        }
    }

    private static async Task<(KestrelReceiverHost Host, int Port, HttpClient Client)> StartEndpointAsync(
        PhoneLinkDb db,
        PhoneLink.Core.Identity.IDeviceIdentityProvider identity,
        PhoneLink.Core.Security.ITlsCertificateProvider certificate,
        PhoneLink.Core.Pairing.IPairedDeviceRepository deviceRepository,
        string baseDir)
    {
        var port = GetFreePort();
        var pairing = new PhoneLink.Infrastructure.Pairing.PairingSessionService(
            db, identity, certificate, "127.0.0.1", port,
            Microsoft.Extensions.Logging.Abstractions.NullLogger<PhoneLink.Infrastructure.Pairing.PairingSessionService>.Instance);
        var validator = new PhoneLink.Infrastructure.Auth.PairedDeviceTokenValidator(db, deviceRepository);
        var bus = new TransferEventBus();
        var transferService = new PhoneLink.Infrastructure.Transfers.TransferService(
            new PhoneLink.Infrastructure.Transfers.TransferRepository(db),
            new PhoneLink.Infrastructure.Transfers.TransferFileStore(new AppPaths(baseDir)),
            bus, Microsoft.Extensions.Logging.Abstractions.NullLogger<PhoneLink.Infrastructure.Transfers.TransferService>.Instance);

        var host = new KestrelReceiverHost(
            new ReceiverOptions { Port = port }, identity, validator, transferService,
            Microsoft.Extensions.Logging.Abstractions.NullLoggerFactory.Instance, certificate, pairing, deviceRepository,
            new PhoneLink.Infrastructure.Feedback.ScannerFeedbackService(
                new AppPaths(baseDir),
                Microsoft.Extensions.Logging.Abstractions.NullLogger<PhoneLink.Infrastructure.Feedback.ScannerFeedbackService>.Instance));
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
        return (host, port, client);
    }

    private static int GetFreePort()
    {
        var listener = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        return port;
    }

    private static Microsoft.Data.Sqlite.SqliteConnection OpenDb(ReceiverTestHost host)
    {
        var connection = new Microsoft.Data.Sqlite.SqliteConnection(
            new Microsoft.Data.Sqlite.SqliteConnectionStringBuilder
            {
                DataSource = host.Paths.DbPath,
                Mode = Microsoft.Data.Sqlite.SqliteOpenMode.ReadWrite,
            }.ToString());
        connection.Open();
        return connection;
    }

    [Fact]
    public async Task Upload_Jpeg_SucceedsAndPersistsFile()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();
        var bytes = TestImages.TinyJpeg;
        var sha = TestImages.Sha256Hex(bytes);

        var response = await host.UploadAsync(bytes, "image/jpeg", sha256: sha);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        var transferId = body.GetProperty("transferId").GetString();
        Assert.Equal("completed", body.GetProperty("status").GetString());

        var record = await host.Repository.GetByIdAsync(transferId!, CancellationToken.None);
        Assert.NotNull(record);
        Assert.Equal(TransferStatus.Completed, record!.Status);
        Assert.True(File.Exists(record.LocalFilePath));
        Assert.Equal(sha, TestImages.Sha256Hex(await File.ReadAllBytesAsync(record.LocalFilePath)));
        Assert.StartsWith(host.Paths.InboxDir, record.LocalFilePath);
        Assert.Equal("image/jpeg", record.MimeType);
    }

    [Fact]
    public async Task Upload_Png_Succeeds()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();
        var bytes = TestImages.TinyPng;
        var sha = TestImages.Sha256Hex(bytes);

        var response = await host.UploadAsync(bytes, "image/png", sha256: sha);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        var record = await host.Repository.GetByIdAsync(body.GetProperty("transferId").GetString()!, CancellationToken.None);
        Assert.NotNull(record);
        Assert.Equal("image/png", record!.MimeType);
        Assert.EndsWith(".png", record.LocalFilePath);
    }

    [Fact]
    public async Task Upload_ContentDoesNotMatchDeclaredMime_Rejected()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();

        var response = await host.UploadAsync(TestImages.TinyPng, "image/jpeg");
        Assert.Equal(HttpStatusCode.UnsupportedMediaType, response.StatusCode);

        var error = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("UNSUPPORTED_MEDIA_TYPE", error.GetProperty("code").GetString());
    }

    [Fact]
    public async Task Upload_UnknownMimeType_Rejected()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();

        var response = await host.UploadAsync(TestImages.TinyJpeg, "text/plain");
        Assert.Equal(HttpStatusCode.UnsupportedMediaType, response.StatusCode);

        var error = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("UNSUPPORTED_MEDIA_TYPE", error.GetProperty("code").GetString());
    }

    [Fact]
    public async Task Upload_FileTooLarge_RejectedAndTempCleaned()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();
        var tooBig = TestImages.FakeJpegHeaderWithZeros(25L * 1024 * 1024 + 1);

        var response = await host.UploadAsync(tooBig, "image/jpeg", sha256: TestImages.Sha256Hex(tooBig));
        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, response.StatusCode);

        var error = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("FILE_TOO_LARGE", error.GetProperty("code").GetString());
        Assert.Empty(Directory.GetFiles(host.Paths.TempDir));
    }

    [Fact]
    public async Task Upload_HashMismatch_RejectedAndTempCleaned()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();

        var response = await host.UploadAsync(
            TestImages.TinyJpeg, "image/jpeg", sha256: new string('0', 64));
        Assert.Equal(HttpStatusCode.UnprocessableEntity, response.StatusCode);

        var error = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("TRANSFER_HASH_MISMATCH", error.GetProperty("code").GetString());
        Assert.True(error.GetProperty("retryable").GetBoolean());
        Assert.Empty(Directory.GetFiles(host.Paths.TempDir));
    }

    [Fact]
    public async Task Upload_PathTraversalFileName_FileLandsSafelyInInbox()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();
        var bytes = TestImages.TinyJpeg;

        var response = await host.UploadAsync(bytes, "image/jpeg", fileName: "../../../evil.jpg");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        var transferId = body.GetProperty("transferId").GetString()!;

        var record = await host.Repository.GetByIdAsync(transferId, CancellationToken.None);
        Assert.NotNull(record);
        Assert.Equal("evil.jpg", record!.OriginalFileName);
        Assert.StartsWith(host.Paths.InboxDir, record.LocalFilePath);
        Assert.DoesNotContain("..", record.LocalFilePath);
        Assert.True(File.Exists(record.LocalFilePath));
    }

    [Fact]
    public async Task Upload_DuplicateTransferId_IsIdempotent()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();
        var bytes = TestImages.TinyJpeg;
        var transferId = $"t-dup-{Guid.NewGuid():N}";

        var first = await host.UploadAsync(bytes, "image/jpeg", transferId: transferId);
        var second = await host.UploadAsync(bytes, "image/jpeg", transferId: transferId);

        Assert.Equal(HttpStatusCode.OK, first.StatusCode);
        Assert.Equal(HttpStatusCode.OK, second.StatusCode);

        var firstBody = await first.Content.ReadFromJsonAsync<JsonElement>();
        var secondBody = await second.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal(firstBody.GetProperty("transferId").GetString(),
            secondBody.GetProperty("transferId").GetString());

        var record = await host.Repository.GetByIdAsync(transferId, CancellationToken.None);
        Assert.NotNull(record);
        Assert.Equal(TransferStatus.Completed, record!.Status);

        var files = Directory.GetFiles(host.Paths.InboxDir, "*", SearchOption.AllDirectories);
        Assert.Single(files);
    }

    [Fact]
    public async Task Upload_InvalidToken_Unauthorized()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();

        var response = await host.UploadAsync(
            TestImages.TinyJpeg, "image/jpeg", tokenOverride: "not-the-token");
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);

        var error = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("AUTH_INVALID", error.GetProperty("code").GetString());
    }

    [Fact]
    public async Task Upload_MissingToken_Unauthorized()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();

        var response = await host.UploadAsync(TestImages.TinyJpeg, "image/jpeg", tokenOverride: null!);
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task Upload_WithoutFilePart_Rejected()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();

        var metadata = new
        {
            transferId = $"t-{Guid.NewGuid():N}",
            senderDeviceId = "mobile-test-1",
            originalFileName = "x.jpg",
            mimeType = "image/jpeg",
            fileSize = 1,
            sha256 = new string('a', 64),
        };
        using var content = new MultipartFormDataContent();
        content.Add(new StringContent(JsonSerializer.Serialize(metadata)), "metadata");

        using var request = new HttpRequestMessage(HttpMethod.Post, "/v1/transfers") { Content = content };
        request.Headers.Authorization = new("Bearer", host.Token);

        var response = await host.Client.SendAsync(request);
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var error = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("INVALID_REQUEST", error.GetProperty("code").GetString());
    }

    [Fact]
    public async Task Upload_BadMetadataJson_Rejected()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();

        using var content = new MultipartFormDataContent();
        content.Add(new StringContent("not-json"), "metadata");
        content.Add(new ByteArrayContent(TestImages.TinyJpeg), "file", "photo.jpg");

        using var request = new HttpRequestMessage(HttpMethod.Post, "/v1/transfers") { Content = content };
        request.Headers.Authorization = new("Bearer", host.Token);

        var response = await host.Client.SendAsync(request);
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task GetStatus_UnknownTransfer_NotFound()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();

        using var request = new HttpRequestMessage(HttpMethod.Get, "/v1/transfers/does-not-exist");
        request.Headers.Authorization = new("Bearer", host.Token);

        var response = await host.Client.SendAsync(request);
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task Events_PublishedOnSuccessfulUpload()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();
        var received = new TaskCompletionSource<TransferRecord>(
            TaskCreationOptions.RunContinuationsAsynchronously);

        void Handler(object? sender, TransferRecord record) => received.TrySetResult(record);
        host.Bus.Received += Handler;
        try
        {
            var response = await host.UploadAsync(TestImages.TinyJpeg, "image/jpeg");
            Assert.Equal(HttpStatusCode.OK, response.StatusCode);

            var record = await received.Task.WaitAsync(TimeSpan.FromSeconds(10));
            Assert.Equal(TransferStatus.Completed, record.Status);
            Assert.True(File.Exists(record.LocalFilePath));
        }
        finally
        {
            host.Bus.Received -= Handler;
        }
    }
}