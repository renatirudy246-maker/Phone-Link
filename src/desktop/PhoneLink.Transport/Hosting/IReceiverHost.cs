namespace PhoneLink.Transport.Hosting;

/// <summary>
/// 局域网 HTTPS Receiver。支持暂停接收（Pause）：
/// 暂停期间 POST /v1/transfers 返回 503 SERVICE_PAUSED，health/pair 保持可用。
/// </summary>
public interface IReceiverHost : IAsyncDisposable
{
    Task StartAsync(CancellationToken cancellationToken);

    Task StopAsync(CancellationToken cancellationToken);

    bool IsPaused { get; }

    void Pause();

    void Resume();
}