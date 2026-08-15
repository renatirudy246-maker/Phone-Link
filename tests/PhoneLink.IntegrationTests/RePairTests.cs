using System.Net;
using System.Net.Http.Headers;
using PhoneLink.Core.Pairing;

namespace PhoneLink.IntegrationTests;

/// <summary>
/// 稳定 DeviceId 语义：同一设备（安装级 DeviceId 不变）重复配对必须
/// 更新原行而不是新增行，轮换 token，并可将已撤销设备恢复信任。
/// </summary>
public class RePairTests
{
    private const string StableMobileId = "mobile-00000000000000000000000000000000";

    [Fact]
    public async Task SameDeviceId_RePair_UpdatesSingleRowAndRotatesToken()
    {
        await using var host = await ReceiverTestHost.StartAsync();

        var firstToken = await host.PairAsync(
            mobileDeviceId: StableMobileId, mobileDeviceName: "MEIZU 21", platform: "android");

        var devices = await host.DeviceRepository.ListAllAsync(CancellationToken.None);
        var row = Assert.Single(devices);
        Assert.Equal(StableMobileId, row.DeviceId);
        Assert.Equal("MEIZU 21", row.DisplayName);
        Assert.True(row.IsTrusted);
        var firstTokenRef = row.AuthTokenReference;

        // 旧 token 在轮换前有效
        Assert.Equal(HttpStatusCode.OK, await HealthAsync(host.Client, firstToken));

        // 同一 DeviceId 再次扫码配对（等价于清除应用数据后重装再配对）
        var secondToken = await host.PairAsync(
            mobileDeviceId: StableMobileId, mobileDeviceName: "MEIZU 21", platform: "android");

        devices = await host.DeviceRepository.ListAllAsync(CancellationToken.None);
        row = Assert.Single(devices); // 不新增行
        Assert.True(row.IsTrusted);
        Assert.NotEqual(firstTokenRef, row.AuthTokenReference); // token 已轮换

        // 旧 token 失效，新 token 有效
        Assert.Equal(HttpStatusCode.Unauthorized, await HealthAsync(host.Client, firstToken));
        Assert.Equal(HttpStatusCode.OK, await HealthAsync(host.Client, secondToken));
    }

    [Fact]
    public async Task SameDeviceId_RePair_AfterRevoke_RestoresTrustInSingleRow()
    {
        await using var host = await ReceiverTestHost.StartAsync();

        var token = await host.PairAsync(mobileDeviceId: StableMobileId, mobileDeviceName: "MEIZU 21");
        Assert.Equal(HttpStatusCode.OK, await HealthAsync(host.Client, token));

        // 桌面端撤销信任 → 认证立即被拒（已撤销设备：403）
        await host.DeviceRepository.SetRevokedAsync(StableMobileId, revoked: true, CancellationToken.None);
        Assert.Equal(HttpStatusCode.Forbidden, await HealthAsync(host.Client, token));

        // 手机端再次扫码配对（同一 DeviceId）→ 单行、恢复信任、新 token 可用
        var newToken = await host.PairAsync(mobileDeviceId: StableMobileId, mobileDeviceName: "MEIZU 21");

        var devices = await host.DeviceRepository.ListAllAsync(CancellationToken.None);
        var row = Assert.Single(devices);
        Assert.True(row.IsTrusted);
        Assert.Equal(HttpStatusCode.OK, await HealthAsync(host.Client, newToken));
    }

    [Fact]
    public async Task DifferentDeviceIds_CreateSeparateRows()
    {
        await using var host = await ReceiverTestHost.StartAsync();

        await host.PairAsync(mobileDeviceId: "mobile-aaaa", mobileDeviceName: "Phone A");
        await host.PairAsync(mobileDeviceId: "mobile-bbbb", mobileDeviceName: "Phone B");

        var devices = await host.DeviceRepository.ListAllAsync(CancellationToken.None);
        Assert.Equal(2, devices.Count);
        Assert.Contains(devices, d => d.DeviceId == "mobile-aaaa");
        Assert.Contains(devices, d => d.DeviceId == "mobile-bbbb");
    }

    private static async Task<HttpStatusCode> HealthAsync(HttpClient client, string token)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, "/v1/health");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        using var response = await client.SendAsync(request);
        return response.StatusCode;
    }
}