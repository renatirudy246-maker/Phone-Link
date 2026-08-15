using System.Net.Http.Json;
using System.Text.Json;
using PhoneLink.Core.Errors;
using PhoneLink.Core.Models;
using PhoneLink.Core.Transfers;
using PhoneLink.Infrastructure.Paths;
using PhoneLink.Infrastructure.Transfers;
using Microsoft.Extensions.Logging.Abstractions;

namespace PhoneLink.IntegrationTests;

public class PersistenceTests
{
    [Fact]
    public async Task Restart_PersistsHistory()
    {
        var baseDir = Path.Combine(Path.GetTempPath(), $"phonelink-persist-{Guid.NewGuid():N}");
        try
        {
            string transferId;
            string sha;

            // 第一次启动：上传
            await using (var host = await ReceiverTestHost.StartAsyncWithBaseDir(baseDir))
            {
                var response = await host.UploadAsync(TestImages.TinyJpeg, "image/jpeg");
                Assert.Equal(System.Net.HttpStatusCode.OK, response.StatusCode);
                var body = await response.Content.ReadFromJsonAsync<JsonElement>();
                transferId = body.GetProperty("transferId").GetString()!;
                sha = TestImages.Sha256Hex(TestImages.TinyJpeg);
            }

            // 重启（新进程语义：同数据目录新 host 实例）
            await using (var host = await ReceiverTestHost.StartAsyncWithBaseDir(baseDir))
            {
                var record = await host.Repository.GetByIdAsync(transferId, CancellationToken.None);
                Assert.NotNull(record);
                Assert.Equal(TransferStatus.Completed, record!.Status);
                Assert.True(File.Exists(record.LocalFilePath));
                Assert.Equal(sha, TestImages.Sha256Hex(await File.ReadAllBytesAsync(record.LocalFilePath)));

                var recent = await host.Repository.GetRecentAsync(10, CancellationToken.None);
                Assert.Contains(recent, r => r.TransferId == transferId);

                // HTTP 层面也验证：status 端点能查到历史
                using var request = new System.Net.Http.HttpRequestMessage(
                    System.Net.Http.HttpMethod.Get, $"/v1/transfers/{transferId}");
                request.Headers.Authorization = new("Bearer", host.Token);
                var statusResponse = await host.Client.SendAsync(request);
                Assert.Equal(System.Net.HttpStatusCode.OK, statusResponse.StatusCode);
                var statusBody = await statusResponse.Content.ReadFromJsonAsync<JsonElement>();
                Assert.Equal("completed", statusBody.GetProperty("status").GetString());
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
    public async Task Repository_GetRecent_ReturnsNewestFirst()
    {
        await using var host = await ReceiverTestHost.StartAsync();

        var first = await host.UploadAsync(TestImages.TinyJpeg, "image/jpeg");
        var second = await host.UploadAsync(TestImages.TinyPng, "image/png");
        Assert.Equal(System.Net.HttpStatusCode.OK, first.StatusCode);
        Assert.Equal(System.Net.HttpStatusCode.OK, second.StatusCode);

        var recent = await host.Repository.GetRecentAsync(5, CancellationToken.None);
        Assert.Equal(2, recent.Count);
        Assert.True(recent[0].ReceivedAt >= recent[1].ReceivedAt);
    }
}

public class FileStoreFailureTests
{
    [Fact]
    public async Task DiskWriteFailure_MapsToDiskWriteFailed()
    {
        var baseDir = Path.Combine(Path.GetTempPath(), $"phonelink-diskfail-{Guid.NewGuid():N}");
        try
        {
            var paths = new AppPaths(baseDir);

            // 把 temp 目录替换成一个同名文件，强制写盘失败
            Directory.Delete(paths.TempDir);
            await File.WriteAllTextAsync(paths.TempDir, "not a directory");

            var store = new TransferFileStore(paths);
            var manifest = new TransferManifest(
                TransferId: $"t-{Guid.NewGuid():N}",
                SenderDeviceId: "mobile-test-1",
                OriginalFileName: "x.jpg",
                MimeType: "image/jpeg",
                FileSize: TestImages.TinyJpeg.Length,
                Width: null,
                Height: null,
                Sha256: TestImages.Sha256Hex(TestImages.TinyJpeg),
                CapturedAt: DateTimeOffset.UtcNow,
                SentAt: DateTimeOffset.UtcNow,
                Purpose: TransferPurpose.Question);

            var ex = await Assert.ThrowsAsync<TransferProcessingException>(() =>
                store.WriteAsync(
                    new MemoryStream(TestImages.TinyJpeg),
                    manifest.TransferId,
                    manifest.Sha256,
                    manifest.MimeType,
                    25 * 1024 * 1024,
                    CancellationToken.None));

            Assert.Equal(ErrorCodes.DiskWriteFailed, ex.Error.Code);
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
}