using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using PhoneLink.Core;
using PhoneLink.Infrastructure.Paths;

namespace ProtocolSmokeTest;

/// <summary>
/// Phase 1 协议冒烟测试：不依赖手机，从本机向运行中的 Desktop Receiver 上传真实图片并验证端到端行为。
/// 用法：
///   protocol-smoke-test [--base-url https://127.0.0.1:8484] [--token <token>] [--expect-id <transferId>]
/// </summary>
public static class Program
{
    private static readonly byte[] TinyJpeg = Convert.FromBase64String(
        "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AVN//2Q==");

    private static readonly byte[] TinyPng = Convert.FromBase64String(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static int _passed;
    private static int _failed;

    public static async Task<int> Main(string[] args)
    {
        var baseUrl = GetArg(args, "--base-url") ?? "https://127.0.0.1:8484";
        var token = GetArg(args, "--token") ?? ReadDevToken();
        var expectId = GetArg(args, "--expect-id");

        if (token is null)
        {
            Console.Error.WriteLine("No token: pass --token or run after Desktop created data/dev-token.txt");
            return 2;
        }

        var handler = new HttpClientHandler
        {
            ServerCertificateCustomValidationCallback = (_, _, _, _) => true,
        };
        using var client = new HttpClient(handler)
        {
            BaseAddress = new Uri(baseUrl),
            Timeout = TimeSpan.FromSeconds(120),
        };

        Console.WriteLine("Phone-Link protocol smoke test");
        Console.WriteLine($"  base url: {baseUrl}");
        Console.WriteLine($"  token   : {token[..8]}... (short)");
        Console.WriteLine();

        await Check("health (no auth) -> protocol version", async () =>
        {
            var body = await client.GetFromJsonAsync<JsonElement>("/v1/health");
            Assert(body.GetProperty("protocolVersion").GetInt32() == AppInfo.ProtocolVersion, "protocolVersion");
        });

        await Check("health (auth) -> device identity", async () =>
        {
            var request = new HttpRequestMessage(HttpMethod.Get, "/v1/health");
            request.Headers.Authorization = new("Bearer", token);
            var body = await (await client.SendAsync(request)).Content.ReadFromJsonAsync<JsonElement>();
            Assert(body.GetProperty("deviceId").GetString()!.StartsWith("desktop-", StringComparison.Ordinal), "deviceId prefix");
            Assert(body.GetProperty("status").GetString() == "ok", "status");
        });

        string jpegTransferId = await CheckUpload(client, token, "JPEG upload + disk + SHA-256", TinyJpeg, "image/jpeg", "question.jpg", null);

        await CheckUpload(client, token, "PNG upload", TinyPng, "image/png", "diagram.png", null);

        await Check("GET /v1/transfers/{id} -> completed", async () =>
        {
            var request = new HttpRequestMessage(HttpMethod.Get, $"/v1/transfers/{jpegTransferId}");
            request.Headers.Authorization = new("Bearer", token);
            var body = await (await client.SendAsync(request)).Content.ReadFromJsonAsync<JsonElement>();
            Assert(body.GetProperty("status").GetString() == "completed", "status");
        });

        await Check("GET /v1/transfers/unknown -> 404", async () =>
        {
            var request = new HttpRequestMessage(HttpMethod.Get, "/v1/transfers/does-not-exist");
            request.Headers.Authorization = new("Bearer", token);
            var response = await client.SendAsync(request);
            Assert(response.StatusCode == HttpStatusCode.NotFound, "404");
        });

        await Check("fake MIME (PNG bytes declared jpeg) -> 415", async () =>
        {
            var response = await UploadAsync(client, token, TinyPng, "image/jpeg", "fake.jpg");
            Assert(response.StatusCode == HttpStatusCode.UnsupportedMediaType, "415");
            var error = await response.Content.ReadFromJsonAsync<JsonElement>();
            Assert(error.GetProperty("code").GetString() == "UNSUPPORTED_MEDIA_TYPE", "code");
        });

        await Check(">25MB -> 413 FILE_TOO_LARGE", async () =>
        {
            var tooBig = new byte[AppInfo.MaxImageSizeBytes + 1];
            tooBig[0] = 0xFF;
            tooBig[1] = 0xD8;
            tooBig[2] = 0xFF;
            var response = await UploadAsync(client, token, tooBig, "image/jpeg", "big.jpg");
            Assert(response.StatusCode == HttpStatusCode.RequestEntityTooLarge, "413");
            var error = await response.Content.ReadFromJsonAsync<JsonElement>();
            Assert(error.GetProperty("code").GetString() == "FILE_TOO_LARGE", "code");
        });

        await Check("hash mismatch -> 422 TRANSFER_HASH_MISMATCH", async () =>
        {
            var response = await UploadAsync(
                client, token, TinyJpeg, "image/jpeg", "tampered.jpg", sha256: new string('0', 64));
            Assert(response.StatusCode == HttpStatusCode.UnprocessableEntity, "422");
            var error = await response.Content.ReadFromJsonAsync<JsonElement>();
            Assert(error.GetProperty("code").GetString() == "TRANSFER_HASH_MISMATCH", "code");
        });

        string traversalId = await CheckUpload(
            client, token, "path traversal file name -> safe naming", TinyJpeg, "image/jpeg", "../../../evil.jpg", null);

        await Check("duplicate transferId -> idempotent", async () =>
        {
            var id = $"t-dup-{Guid.NewGuid():N}";
            var first = await UploadAsync(client, token, TinyJpeg, "image/jpeg", "dup.jpg", transferId: id);
            var second = await UploadAsync(client, token, TinyJpeg, "image/jpeg", "dup.jpg", transferId: id);
            Assert(first.StatusCode == HttpStatusCode.OK, "first OK");
            Assert(second.StatusCode == HttpStatusCode.OK, "second OK");
            var firstBody = await first.Content.ReadFromJsonAsync<JsonElement>();
            var secondBody = await second.Content.ReadFromJsonAsync<JsonElement>();
            Assert(firstBody.GetProperty("transferId").GetString() == secondBody.GetProperty("transferId").GetString(), "same id");
        });

        await Check("invalid token -> 401", async () =>
        {
            var response = await UploadAsync(client, token, TinyJpeg, "image/jpeg", "x.jpg", tokenOverride: "wrong");
            Assert(response.StatusCode == HttpStatusCode.Unauthorized, "401");
        });

        if (expectId is not null)
        {
            await Check($"history after restart: {expectId} still completed", async () =>
            {
                var request = new HttpRequestMessage(HttpMethod.Get, $"/v1/transfers/{expectId}");
                request.Headers.Authorization = new("Bearer", token);
                var body = await (await client.SendAsync(request)).Content.ReadFromJsonAsync<JsonElement>();
                Assert(body.GetProperty("status").GetString() == "completed", "status completed after restart");
            });
        }

        Console.WriteLine();
        Console.WriteLine($"Result: {_passed} passed, {_failed} failed");
        return _failed == 0 ? 0 : 1;
    }

    private static async Task<string> CheckUpload(
        HttpClient client,
        string token,
        string name,
        byte[] bytes,
        string mime,
        string fileName,
        string? transferId)
    {
        string? uploadId = null;
        await Check(name, async () =>
        {
            var response = await UploadAsync(client, token, bytes, mime, fileName, transferId: transferId);
            Assert(response.StatusCode == HttpStatusCode.OK, "200");

            var body = await response.Content.ReadFromJsonAsync<JsonElement>();
            uploadId = body.GetProperty("transferId").GetString();
            Assert(!string.IsNullOrEmpty(uploadId), "transferId present");
            Assert(body.GetProperty("status").GetString() == "completed", "status completed");

            var localPath = await GetLocalPathAsync(client, token, uploadId!);
            Assert(File.Exists(localPath), $"file exists on disk: {localPath}");
            var onDiskSha = Convert.ToHexString(SHA256.HashData(await File.ReadAllBytesAsync(localPath)));
            Assert(onDiskSha == TestSha(bytes), "SHA-256 matches");
            Assert(localPath.StartsWith(AppPaths.Default.InboxDir, StringComparison.Ordinal), "file under inbox");
        });
        return uploadId!;
    }

    private static async Task<string> GetLocalPathAsync(HttpClient client, string token, string id)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, $"/v1/transfers/{id}");
        request.Headers.Authorization = new("Bearer", token);
        var response = await client.SendAsync(request);
        Assert(response.StatusCode == HttpStatusCode.OK, "status 200");
        var dto = await response.Content.ReadFromJsonAsync<TransferStatusDto>();
        return dto.LocalFilePath;
    }

    private static async Task<HttpResponseMessage> UploadAsync(
        HttpClient client,
        string token,
        byte[] fileBytes,
        string mimeType,
        string fileName,
        string? sha256 = null,
        string? transferId = null,
        string tokenOverride = "__default__")
    {
        var id = transferId ?? $"t-{Guid.NewGuid():N}";
        var metadata = new
        {
            transferId = id,
            senderDeviceId = "smoke-test",
            originalFileName = fileName,
            mimeType,
            fileSize = fileBytes.LongLength,
            width = 1,
            height = 1,
            sha256 = sha256 ?? TestSha(fileBytes),
            capturedAt = DateTimeOffset.UtcNow,
            sentAt = DateTimeOffset.UtcNow,
            purpose = "Question",
        };

        using var content = new MultipartFormDataContent();
        content.Add(new StringContent(JsonSerializer.Serialize(metadata)), "metadata");
        content.Add(new ByteArrayContent(fileBytes), "file", "photo.jpg");

        using var request = new HttpRequestMessage(HttpMethod.Post, "/v1/transfers") { Content = content };
        var effectiveToken = tokenOverride == "__default__" ? token : tokenOverride;
        if (effectiveToken is not null)
        {
            request.Headers.Authorization = new("Bearer", effectiveToken);
        }

        return await client.SendAsync(request);
    }

    private static string TestSha(byte[] bytes)
        => Convert.ToHexString(SHA256.HashData(bytes));

    private static string? ReadDevToken()
    {
        try
        {
            var path = AppPaths.Default.DevTokenPath;
            return File.Exists(path) ? File.ReadAllText(path).Trim() : null;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"Failed to read dev token: {ex.Message}");
            return null;
        }
    }

    private static string? GetArg(string[] args, string name)
    {
        for (int i = 0; i < args.Length - 1; i++)
        {
            if (args[i] == name)
            {
                return args[i + 1];
            }
        }

        return null;
    }

    private static async Task Check(string name, Func<Task> action)
    {
        try
        {
            await action();
            _passed++;
            Console.WriteLine($"  [PASS] {name}");
        }
        catch (Exception ex)
        {
            _failed++;
            Console.WriteLine($"  [FAIL] {name}: {ex.Message}");
        }
    }

    private static void Assert(bool condition, string what)
    {
        if (!condition)
        {
            throw new InvalidOperationException($"assertion failed: {what}");
        }
    }
}

public sealed record TransferStatusDto(
    string TransferId,
    string Status,
    DateTimeOffset ReceivedAt,
    string? ErrorCode,
    string LocalFilePath);