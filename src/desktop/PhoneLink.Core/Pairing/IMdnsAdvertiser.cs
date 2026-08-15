namespace PhoneLink.Core.Pairing;

/// <summary>mDNS/DNS-SD 广告器：发布 _phonelink._tcp.local 服务与 TXT 元数据。</summary>
public interface IMdnsAdvertiser : IAsyncDisposable
{
    Task StartAsync(MdnsAdvertisement advertisement, CancellationToken cancellationToken);

    Task StopAsync(CancellationToken cancellationToken);
}

public sealed record MdnsAdvertisement(
    string InstanceName,
    int Port,
    string DeviceId,
    string DeviceName);

/// <summary>
/// mDNS TXT 记录构建：只允许非敏感元数据（version/deviceId/name）。
/// 纯函数以便单元测试。
/// </summary>
public static class MdnsTxt
{
    public static IReadOnlyDictionary<string, string> Build(int protocolVersion, string deviceId, string deviceName)
    {
        return new Dictionary<string, string>
        {
            ["version"] = protocolVersion.ToString(),
            ["deviceId"] = deviceId,
            ["name"] = deviceName,
        };
    }
}