using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using PhoneLink.Core.Models;

namespace PhoneLink.IntegrationTests;

public class ReceiverHttpTests
{
    [Fact]
    public async Task Health_WithoutToken_ReturnsProtocolVersionOnly()
    {
        await using var host = await ReceiverTestHost.StartAsync();

        var response = await host.Client.GetAsync("/v1/health");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal(1, body.GetProperty("protocolVersion").GetInt32());
        Assert.False(body.TryGetProperty("deviceId", out _));
    }

    [Fact]
    public async Task Health_WithToken_ReturnsDeviceInfo()
    {
        await using var host = await ReceiverTestHost.StartAsync();

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
    public async Task Health_WrongToken_ReturnsMinimal()
    {
        await using var host = await ReceiverTestHost.StartAsync();

        using var request = new HttpRequestMessage(HttpMethod.Get, "/v1/health");
        request.Headers.Authorization = new("Bearer", "wrong-token");
        var response = await host.Client.SendAsync(request);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.False(body.TryGetProperty("deviceId", out _));
    }

    [Fact]
    public async Task Upload_Jpeg_SucceedsAndPersistsFile()
    {
        await using var host = await ReceiverTestHost.StartAsync();
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

        var response = await host.UploadAsync(TestImages.TinyPng, "image/jpeg");
        Assert.Equal(HttpStatusCode.UnsupportedMediaType, response.StatusCode);

        var error = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("UNSUPPORTED_MEDIA_TYPE", error.GetProperty("code").GetString());
    }

    [Fact]
    public async Task Upload_UnknownMimeType_Rejected()
    {
        await using var host = await ReceiverTestHost.StartAsync();

        var response = await host.UploadAsync(TestImages.TinyJpeg, "text/plain");
        Assert.Equal(HttpStatusCode.UnsupportedMediaType, response.StatusCode);

        var error = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("UNSUPPORTED_MEDIA_TYPE", error.GetProperty("code").GetString());
    }

    [Fact]
    public async Task Upload_FileTooLarge_RejectedAndTempCleaned()
    {
        await using var host = await ReceiverTestHost.StartAsync();
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

        var response = await host.UploadAsync(TestImages.TinyJpeg, "image/jpeg", tokenOverride: null!);
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task Upload_WithoutFilePart_Rejected()
    {
        await using var host = await ReceiverTestHost.StartAsync();

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

        using var request = new HttpRequestMessage(HttpMethod.Get, "/v1/transfers/does-not-exist");
        request.Headers.Authorization = new("Bearer", host.Token);

        var response = await host.Client.SendAsync(request);
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task Events_PublishedOnSuccessfulUpload()
    {
        await using var host = await ReceiverTestHost.StartAsync();
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