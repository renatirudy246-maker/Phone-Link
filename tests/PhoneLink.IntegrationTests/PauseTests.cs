using System.Net;
using System.Text.Json;
using Xunit;

namespace PhoneLink.IntegrationTests;

public sealed class PauseTests
{
    [Fact]
    public async Task Paused_Upload_ReturnsServicePaused()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();

        host.Receiver.Pause();
        try
        {
            var response = await host.UploadAsync(TestImages.TinyJpeg, "image/jpeg");
            Assert.Equal(HttpStatusCode.ServiceUnavailable, response.StatusCode);

            var body = await response.Content.ReadAsStringAsync();
            using var json = JsonDocument.Parse(body);
            Assert.Equal("SERVICE_PAUSED", json.RootElement.GetProperty("code").GetString());
            Assert.True(json.RootElement.GetProperty("retryable").GetBoolean());
        }
        finally
        {
            host.Receiver.Resume();
        }
    }

    [Fact]
    public async Task Paused_HealthStillWorks()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();

        host.Receiver.Pause();
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, "/v1/health");
            request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", host.Token);
            var response = await host.Client.SendAsync(request);
            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        }
        finally
        {
            host.Receiver.Resume();
        }
    }

    [Fact]
    public async Task Resume_AfterPause_UploadSucceeds()
    {
        await using var host = await ReceiverTestHost.StartAsync();
        await host.PairAsync();

        host.Receiver.Pause();
        var paused = await host.UploadAsync(TestImages.TinyJpeg, "image/jpeg");
        Assert.Equal(HttpStatusCode.ServiceUnavailable, paused.StatusCode);

        host.Receiver.Resume();
        var resumed = await host.UploadAsync(TestImages.TinyJpeg, "image/jpeg");
        Assert.Equal(HttpStatusCode.OK, resumed.StatusCode);
    }
}