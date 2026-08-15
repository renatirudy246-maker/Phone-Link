using System.Runtime.InteropServices;
using Microsoft.Extensions.Logging;
using PhoneLink.Core;
using PhoneLink.Core.Pairing;

namespace PhoneLink.Transport.Discovery;

/// <summary>
/// 使用 Windows 内建 mDNS responder（dnsapi.dll DNS-SD API，Windows 10 1803+）
/// 注册 _phonelink._tcp.local 服务。TXT 只含非敏感元数据（version/deviceId/name）。
/// 平台不支持时静默降级（配对仍可通过 QR 完成，仅发现功能不可用）。
/// 结构体布局与 Sunshine/Apollo 生产实现一致：unicastEnabled=0 走 mDNS。
/// </summary>
public sealed class WindowsMdnsAdvertiser : IMdnsAdvertiser
{
    private readonly ILogger<WindowsMdnsAdvertiser> _logger;
    private readonly List<nint> _allocated = [];
    private readonly object _sync = new();
    private bool _started;
    private nint _requestPtr;
    private RegisterCompletionCallback? _callback;
    private string? _instanceName;

    public WindowsMdnsAdvertiser(ILogger<WindowsMdnsAdvertiser> logger)
    {
        _logger = logger;
    }

    public Task StartAsync(MdnsAdvertisement advertisement, CancellationToken cancellationToken)
    {
        _instanceName = $"{advertisement.InstanceName}.{AppInfo.ServiceType}";
        var txt = MdnsTxt.Build(AppInfo.ProtocolVersion, advertisement.DeviceId, advertisement.DeviceName);
        Register(advertisement.Port, txt);
        return Task.CompletedTask;
    }

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        if (!_started)
        {
            return;
        }

        lock (_sync)
        {
            if (_requestPtr != nint.Zero)
            {
                DnsServiceDeRegister(_requestPtr, nint.Zero);
            }

            _requestPtr = nint.Zero;
        }

        FreeAllocations();
        _started = false;
        _logger.LogInformation("mDNS advertisement stopped: {InstanceName}.", _instanceName);
        await Task.CompletedTask.ConfigureAwait(false);
    }

    public async ValueTask DisposeAsync()
    {
        if (_started)
        {
            await StopAsync(CancellationToken.None).ConfigureAwait(false);
        }

        GC.SuppressFinalize(this);
    }

    private void Register(int port, IReadOnlyDictionary<string, string> txt)
    {
        if (!OperatingSystem.IsWindows())
        {
            _logger.LogWarning("mDNS advertisement skipped: Windows-only feature.");
            return;
        }

        try
        {
            var instanceNamePtr = AllocUtf16(_instanceName!);
            var hostNamePtr = AllocUtf16($"{Environment.MachineName}.local");
            var keyPtrs = AllocStringArray(txt.Keys.ToArray());
            var valuePtrs = AllocStringArray(txt.Values.ToArray());

            var instance = new DnsServiceInstance
            {
                InstanceName = instanceNamePtr,
                HostName = hostNamePtr,
                IpAddresses = nint.Zero,
                Ip6Address = nint.Zero,
                Port = (ushort)port,
                Priority = 0,
                Weight = 0,
                PropertyCount = (uint)txt.Count,
                Keys = keyPtrs,
                Values = valuePtrs,
            };

            var instancePtr = AllocStruct(instance);
            _callback = OnRegisterComplete;

            var request = new DnsServiceRegisterRequest
            {
                Version = DnsServiceRegisterRequestVersion1,
                InterfaceIndex = 0,
                ServiceInstance = instancePtr,
                CompletionCallback = Marshal.GetFunctionPointerForDelegate(_callback),
                QueryContext = nint.Zero,
                Credentials = nint.Zero,
                UnicastEnabled = 0,
            };

            _requestPtr = AllocStruct(request);
            var status = DnsServiceRegister(_requestPtr, nint.Zero);

            if (status != 0 && status != DnsRequestPending)
            {
                _logger.LogWarning(
                    "mDNS registration failed with status {Status}; discovery will be unavailable for this session.",
                    status);
                FreeAllocations();
                _requestPtr = nint.Zero;
                return;
            }

            _started = true;
            _logger.LogInformation("mDNS advertisement registered: {InstanceName} (port {Port}).", _instanceName, port);
        }
        catch (Exception ex) when (ex is DllNotFoundException or EntryPointNotFoundException or BadImageFormatException)
        {
            _logger.LogWarning(ex, "mDNS advertisement unavailable on this Windows version.");
            FreeAllocations();
            _requestPtr = nint.Zero;
        }
    }

    private void OnRegisterComplete(uint status, nint queryContext, nint instance)
    {
        if (status != 0)
        {
            _logger.LogWarning("mDNS registration completion reported status {Status}.", status);
        }

        // 文档要求：回调返回的 instance 由调用方用 DnsServiceFreeInstance 释放。
        if (instance != nint.Zero)
        {
            DnsServiceFreeInstance(instance);
        }
    }

    private nint AllocUtf16(string value)
    {
        var ptr = Marshal.StringToHGlobalUni(value);
        _allocated.Add(ptr);
        return ptr;
    }

    private nint AllocStruct<T>(T value) where T : struct
    {
        var ptr = Marshal.AllocHGlobal(Marshal.SizeOf<T>());
        Marshal.StructureToPtr(value, ptr, fDeleteOld: false);
        _allocated.Add(ptr);
        return ptr;
    }

    private nint AllocMemory(int size)
    {
        var ptr = Marshal.AllocHGlobal(size);
        _allocated.Add(ptr);
        return ptr;
    }

    private nint AllocStringArray(string[] values)
    {
        var ptrs = new nint[values.Length];
        for (int i = 0; i < values.Length; i++)
        {
            ptrs[i] = AllocUtf16(values[i]);
        }

        var arrayPtr = AllocMemory(nint.Size * ptrs.Length);
        Marshal.Copy(ptrs, 0, arrayPtr, ptrs.Length);
        return arrayPtr;
    }

    private void FreeAllocations()
    {
        foreach (var ptr in _allocated)
        {
            Marshal.FreeHGlobal(ptr);
        }

        _allocated.Clear();
    }

    private const uint DnsServiceRegisterRequestVersion1 = 1;
    private const uint DnsRequestPending = 9506;

    [StructLayout(LayoutKind.Sequential)]
    private struct DnsServiceInstance
    {
        public nint InstanceName;
        public nint HostName;
        public nint IpAddresses;
        public nint Ip6Address;
        public ushort Port;
        public ushort Priority;
        public ushort Weight;
        public uint PropertyCount;
        public nint Keys;
        public nint Values;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DnsServiceRegisterRequest
    {
        public uint Version;
        public uint InterfaceIndex;
        public nint ServiceInstance;
        public nint CompletionCallback;
        public nint QueryContext;
        public nint Credentials;
        public int UnicastEnabled;
    }

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    private delegate void RegisterCompletionCallback(uint status, nint queryContext, nint instance);

    [DllImport("dnsapi.dll", SetLastError = true)]
    private static extern uint DnsServiceRegister(nint request, nint cancel);

    [DllImport("dnsapi.dll", SetLastError = true)]
    private static extern uint DnsServiceDeRegister(nint request, nint cancel);

    [DllImport("dnsapi.dll", SetLastError = true)]
    private static extern void DnsServiceFreeInstance(nint instance);
}