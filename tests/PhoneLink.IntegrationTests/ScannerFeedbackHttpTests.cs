using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using PhoneLink.Core;
using PhoneLink.Infrastructure.Paths;

namespace PhoneLink.IntegrationTests;

/// <summary>
/// Phase 4B-D2：POST /api/v1/scanner-feedback 端到端验证。
/// </summary>
public class ScannerFeedbackHttpTests
{
    private const string ModelSha256 = "aaef348eb81709d26f7e8974401795b141d70ba88bc69792c779fbae102eadaa";

    private static string BuildMetadataJson(
        string sampleId,
        string sourceSha256,
        string? reason = "USER_CORRECTED",
        string status = "Detected",
        bool predictedQuadNull = false,
        bool predictionMissing = false)
        => $$"""
            {
              "schemaVersion": 1,
              "sampleId": "{{sampleId}}",
              "createdAtUtc": "2026-08-16T12:00:00Z",
              "labelSource": "user_confirmed_quad",
              "source": { "width": 1000, "height": 2000, "sha256": "{{sourceSha256}}" },
              "model": { "name": "DocQuadNet-256", "sha256": "{{ModelSha256}}" },
              "detection": {
                "status": "{{status}}",
                "confidence": 0.9,
                "qualityReason": null,
                "maskAreaRatio": 0.7,
                "heatmap": null
              },
              "predictedQuad": {{(predictedQuadNull ? "null" : "{\n                \"tl\": [0.1, 0.1], \"tr\": [0.9, 0.1], \"br\": [0.9, 0.9], \"bl\": [0.1, 0.9]\n              }")}},
              "correctedQuad": {
                "tl": [0.1, 0.1], "tr": [0.95, 0.05], "br": [0.9, 0.9], "bl": [0.1, 0.9]
              },
              "correction": {
                "meanDelta": 0.01,
                "maxDelta": 0.035,
                "adjustedCorners": ["TR"],
                "predictionMissing": {{(predictionMissing ? "true" : "false")}}
              },
              "reason": "{{reason}}"
            }
            """;

    private static async Task<HttpResponseMessage> PostFeedbackAsync(
        ReceiverTestHost host,
        string metadataJson,
        byte[] fileBytes,
        string? tokenOverride = null)
    {
        using var content = new MultipartFormDataContent();
        content.Add(new StringContent(metadataJson), "metadata");
        content.Add(new ByteArrayContent(fileBytes), "file", "source.jpg");

        var request = new HttpRequestMessage(HttpMethod.Post, "/api/v1/scanner-feedback")
        {
            Content = content,
        };
        var token = tokenOverride ?? host.Token;
        if (token is not null)
        {
            request.Headers.Authorization = new("Bearer", token);
        }

        return await host.Client.SendAsync(request);
    }

    private static List<string> FindSampleDirs(AppPaths paths, string sampleId)
        => Directory.EnumerateDirectories(paths.ScannerFeedbackDir, "*", SearchOption.AllDirectories)
            .Where(d => Path.GetFileName(d) == sampleId)
            .ToList();

    [Fact]
    public async Task J_ValidUpload_StoredAtomicallyOnDisk()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();
        var sampleId = $"sf-j-{Guid.NewGuid():N}";
        var jpeg = TestImages.TinyJpeg;
        var metadata = BuildMetadataJson(sampleId, TestImages.Sha256Hex(jpeg));

        var response = await PostFeedbackAsync(host, metadata, jpeg);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal(sampleId, body.GetProperty("sampleId").GetString());
        Assert.Equal("stored", body.GetProperty("status").GetString());

        var dirs = FindSampleDirs(host.Paths, sampleId);
        Assert.Single(dirs);
        var sampleDir = dirs[0];

        var sourceBytes = await File.ReadAllBytesAsync(Path.Combine(sampleDir, "source.jpg"));
        Assert.Equal(jpeg, sourceBytes);

        var storedMeta = JsonDocument.Parse(
            await File.ReadAllTextAsync(Path.Combine(sampleDir, "metadata.json"))).RootElement;
        Assert.Equal(1, storedMeta.GetProperty("schemaVersion").GetInt32());
        Assert.Equal(sampleId, storedMeta.GetProperty("sampleId").GetString());
        Assert.Equal("user_confirmed_quad", storedMeta.GetProperty("labelSource").GetString());
        Assert.Equal("USER_CORRECTED", storedMeta.GetProperty("reason").GetString());
        Assert.Equal("DocQuadNet-256", storedMeta.GetProperty("model").GetProperty("name").GetString());
        Assert.Equal(ModelSha256, storedMeta.GetProperty("model").GetProperty("sha256").GetString());
        Assert.Equal("Detected", storedMeta.GetProperty("detection").GetProperty("status").GetString());
        Assert.True(storedMeta.TryGetProperty("predictedQuad", out _));
        Assert.Equal("TR", storedMeta.GetProperty("correction").GetProperty("adjustedCorners")[0].GetString());
    }

    [Fact]
    public async Task K_DuplicateSampleId_ReturnsAlreadyStoredWithoutDuplicate()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();
        var sampleId = $"sf-k-{Guid.NewGuid():N}";
        var jpeg = TestImages.TinyJpeg;
        var metadata = BuildMetadataJson(sampleId, TestImages.Sha256Hex(jpeg));

        var first = await PostFeedbackAsync(host, metadata, jpeg);
        Assert.Equal(HttpStatusCode.OK, first.StatusCode);
        Assert.Equal("stored", (await first.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("status").GetString());

        var second = await PostFeedbackAsync(host, metadata, jpeg);
        Assert.Equal(HttpStatusCode.OK, second.StatusCode);
        Assert.Equal(
            "already_stored",
            (await second.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("status").GetString());

        Assert.Single(FindSampleDirs(host.Paths, sampleId));
    }

    [Fact]
    public async Task L_UnauthorizedUpload_Rejected()
    {
        await using var host = await ReceiverTestHost.StartAsync();

        var response = await PostFeedbackAsync(
            host, BuildMetadataJson($"sf-l-{Guid.NewGuid():N}", TestImages.Sha256Hex(TestImages.TinyJpeg)), TestImages.TinyJpeg,
            tokenOverride: "wrong-token");
        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task M_Sha256Mismatch_RejectedWithFeedbackHashMismatch()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();
        var sampleId = $"sf-m-{Guid.NewGuid():N}";
        var metadata = BuildMetadataJson(sampleId, "11".Repeat64());

        var response = await PostFeedbackAsync(host, metadata, TestImages.TinyJpeg);
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);

        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("FEEDBACK_HASH_MISMATCH", body.GetProperty("code").GetString());
        Assert.Empty(FindSampleDirs(host.Paths, sampleId));
    }

    [Fact]
    public async Task N_OversizedFile_RejectedWithFeedbackTooLarge()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();
        var sampleId = $"sf-n-{Guid.NewGuid():N}";
        var oversized = TestImages.FakeJpegHeaderWithZeros(AppInfo.MaxImageSizeBytes + 1);
        var metadata = BuildMetadataJson(sampleId, TestImages.Sha256Hex(oversized));

        var response = await PostFeedbackAsync(host, metadata, oversized);
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);

        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("FEEDBACK_TOO_LARGE", body.GetProperty("code").GetString());
        Assert.Empty(FindSampleDirs(host.Paths, sampleId));
    }

    [Fact]
    public async Task O_MalformedMetadata_RejectedWithFeedbackInvalid()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();

        var response = await PostFeedbackAsync(host, "{not valid json", TestImages.TinyJpeg);
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);

        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("FEEDBACK_INVALID", body.GetProperty("code").GetString());
        Assert.Empty(Directory.EnumerateFiles(host.Paths.ScannerFeedbackDir, "metadata.json", SearchOption.AllDirectories));
    }

    [Fact]
    public async Task P_NormalTransferFlow_UnaffectedByFeedbackEndpoint()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();

        var feedback = await PostFeedbackAsync(
            host, BuildMetadataJson($"sf-p-{Guid.NewGuid():N}", TestImages.Sha256Hex(TestImages.TinyJpeg)), TestImages.TinyJpeg);
        Assert.Equal(HttpStatusCode.OK, feedback.StatusCode);

        var transfer = await host.UploadAsync(TestImages.TinyJpeg, "image/jpeg");
        Assert.Equal(HttpStatusCode.OK, transfer.StatusCode);
        var body = await transfer.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("completed", body.GetProperty("status").GetString());
        Assert.Equal("completed", body.GetProperty("status").GetString());
    }

    [Fact]
    public async Task J_NotFoundReason_NullPredictedQuadAcceptedAndStored()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();
        var sampleId = $"sf-j2-{Guid.NewGuid():N}";
        var jpeg = TestImages.TinyJpeg;
        var metadata = BuildMetadataJson(
            sampleId, TestImages.Sha256Hex(jpeg), reason: "MODEL_NOT_FOUND", status: "NotFound",
            predictedQuadNull: true, predictionMissing: true);

        var response = await PostFeedbackAsync(host, metadata, jpeg);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("stored", (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("status").GetString());

        var dirs = FindSampleDirs(host.Paths, sampleId);
        Assert.Single(dirs);
        var storedMeta = JsonDocument.Parse(
            await File.ReadAllTextAsync(Path.Combine(dirs[0], "metadata.json"))).RootElement;
        Assert.Equal(JsonValueKind.Null, storedMeta.GetProperty("predictedQuad").ValueKind);
        Assert.Equal("MODEL_NOT_FOUND", storedMeta.GetProperty("reason").GetString());
        Assert.True(storedMeta.GetProperty("correction").GetProperty("predictionMissing").GetBoolean());
    }
}

internal static class Repeat64Extensions
{
    public static string Repeat64(this string value) => string.Concat(Enumerable.Repeat(value, 32));
}