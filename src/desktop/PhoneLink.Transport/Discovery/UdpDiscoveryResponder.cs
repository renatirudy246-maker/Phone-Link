using System.Net;
using System.Net.Sockets;
using System.Text;
using Microsoft.Extensions.Logging;
using PhoneLink.Core.Identity;

namespace PhoneLink.Transport.Discovery;

public interface IUdpDiscoveryResponder : IAsyncDisposable
{
    Task StartAsync(int httpsPort, CancellationToken cancellationToken);
    Task StopAsync(CancellationToken cancellationToken);
}

/// <summary>
/// Phone-Link UDP 局域网发现响应服务（端口 8485）。
/// 监听 PHONELINK_DISCOVER_V1 请求，并通过单播回复 PHONELINK_HERE_V1，
/// 使处于手机热点/局域网漫游环境下的已配对客户端无需重新扫码即可定位电脑端点。
/// 响应报文不包含任何私钥、Token 或敏感身份信息；安全信任完全由后续 TLS 证书指纹校验保证。
/// </summary>
public sealed class UdpDiscoveryResponder : IUdpDiscoveryResponder
{
    public const int DiscoveryPort = 8485;
    private const string RequestHeader = "PHONELINK_DISCOVER_V1";
    private const string ResponseHeader = "PHONELINK_HERE_V1";

    private readonly IDeviceIdentityProvider _identityProvider;
    private readonly ILogger<UdpDiscoveryResponder> _logger;
    private UdpClient? _udpClient;
    private CancellationTokenSource? _cts;
    private Task? _listenerTask;
    private int _httpsPort;
    private bool _started;

    public UdpDiscoveryResponder(
        IDeviceIdentityProvider identityProvider,
        ILogger<UdpDiscoveryResponder> logger)
    {
        _identityProvider = identityProvider;
        _logger = logger;
    }

    public Task StartAsync(int httpsPort, CancellationToken cancellationToken)
    {
        if (_started) return Task.CompletedTask;
        _httpsPort = httpsPort;

        try
        {
            _udpClient = new UdpClient();
            _udpClient.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            _udpClient.Client.Bind(new IPEndPoint(IPAddress.Any, DiscoveryPort));
            _udpClient.EnableBroadcast = true;

            _cts = new CancellationTokenSource();
            _started = true;
            _listenerTask = Task.Run(() => ListenLoopAsync(_cts.Token), CancellationToken.None);
            _logger.LogInformation("UDP Discovery Responder started on 0.0.0.0:{Port} for HTTPS port {HttpsPort}.", DiscoveryPort, _httpsPort);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to start UDP Discovery Responder on port {Port}.", DiscoveryPort);
        }

        return Task.CompletedTask;
    }

    private async Task ListenLoopAsync(CancellationToken ct)
    {
        var client = _udpClient;
        if (client == null) return;

        while (!ct.IsCancellationRequested)
        {
            try
            {
                var receiveResult = await client.ReceiveAsync(ct).ConfigureAwait(false);
                _ = ProcessPacketAsync(receiveResult.Buffer, receiveResult.RemoteEndPoint, ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                if (ct.IsCancellationRequested) break;
                _logger.LogDebug(ex, "Error in UDP receive loop.");
                await Task.Delay(100, ct).ConfigureAwait(false);
            }
        }
    }

    private async Task ProcessPacketAsync(byte[] buffer, IPEndPoint remoteEndPoint, CancellationToken ct)
    {
        try
        {
            var text = Encoding.UTF8.GetString(buffer).Trim();
            var lines = text.Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
            if (lines.Length == 0 || lines[0] != RequestHeader)
            {
                return;
            }

            string nonce = "";
            string targetDeviceId = "";

            for (int i = 1; i < lines.Length; i++)
            {
                var line = lines[i];
                var eq = line.IndexOf('=');
                if (eq <= 0) continue;
                var key = line[..eq].Trim();
                var val = line[(eq + 1)..].Trim();

                if (key.Equals("nonce", StringComparison.OrdinalIgnoreCase)) nonce = val;
                else if (key.Equals("targetDeviceId", StringComparison.OrdinalIgnoreCase)) targetDeviceId = val;
            }

            var identity = await _identityProvider.GetIdentityAsync(ct).ConfigureAwait(false);
            var myDeviceId = identity.DeviceId;

            // 仅在目标 DeviceId 匹配或广播通配符时回复
            if (!string.IsNullOrEmpty(targetDeviceId) &&
                !targetDeviceId.Equals("*", StringComparison.OrdinalIgnoreCase) &&
                !targetDeviceId.Equals(myDeviceId, StringComparison.OrdinalIgnoreCase))
            {
                return;
            }

            var reply = new StringBuilder();
            reply.AppendLine(ResponseHeader);
            reply.AppendLine($"nonce={nonce}");
            reply.AppendLine($"deviceId={myDeviceId}");
            reply.AppendLine($"httpsPort={_httpsPort}");
            reply.AppendLine("protocolVersion=1");

            var replyBytes = Encoding.UTF8.GetBytes(reply.ToString());
            await _udpClient!.SendAsync(replyBytes, replyBytes.Length, remoteEndPoint).ConfigureAwait(false);
            _logger.LogInformation("Replied to UDP discovery request from {RemoteEndPoint} (target={TargetDeviceId}).", remoteEndPoint, targetDeviceId);
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Failed to process discovery packet from {RemoteEndPoint}.", remoteEndPoint);
        }
    }

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        if (!_started) return;
        _started = false;

        try
        {
            _cts?.Cancel();
            _udpClient?.Dispose();
            if (_listenerTask != null)
            {
                await _listenerTask.ConfigureAwait(false);
            }
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Error stopping UDP Discovery Responder.");
        }
        finally
        {
            _cts?.Dispose();
            _cts = null;
            _udpClient = null;
            _listenerTask = null;
        }
        _logger.LogInformation("UDP Discovery Responder stopped.");
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync(CancellationToken.None).ConfigureAwait(false);
        GC.SuppressFinalize(this);
    }
}
