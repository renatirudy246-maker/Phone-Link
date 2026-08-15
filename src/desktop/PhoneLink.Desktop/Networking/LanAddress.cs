using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace PhoneLink.Desktop.Networking;

/// <summary>
/// 选择局域网 IPv4 地址作为 QR 配对 bootstrap 的 host（非长期身份）。
/// 过滤虚拟网卡（VPN/WSL/虚拟机），优先物理以太网/无线接口与 RFC1918 私有网段，
/// 避免把手机不可达的虚拟网卡地址写进二维码。
/// </summary>
public static class LanAddress
{
    private static readonly string[] VirtualAdapterHints =
    [
        "vehernet", "wsl", "hyper-v", "vmware", "virtualbox", "vbox", "docker",
        "vpn", "tap", "tun", "wg", "wg0", "tailscale", "zerotier", "hamachi",
        "vgate", "nord", "openvpn", "loopback", "wintun",
    ];

    public static string? GetFirstIpv4()
    {
        var candidates = new List<(int Priority, string Address)>();

        foreach (var networkInterface in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (networkInterface.OperationalStatus != OperationalStatus.Up)
            {
                continue;
            }

            var kind = networkInterface.NetworkInterfaceType;
            if (kind is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)
            {
                continue;
            }

            var name = networkInterface.Name.ToLowerInvariant();
            var description = networkInterface.Description.ToLowerInvariant();
            if (VirtualAdapterHints.Any(hint =>
                    name.Contains(hint, StringComparison.OrdinalIgnoreCase)
                    || description.Contains(hint, StringComparison.OrdinalIgnoreCase)))
            {
                continue;
            }

            foreach (var address in networkInterface.GetIPProperties().UnicastAddresses)
            {
                if (address.Address.AddressFamily != AddressFamily.InterNetwork
                    || IPAddress.IsLoopback(address.Address))
                {
                    continue;
                }

                int priority = 2;
                if (kind is NetworkInterfaceType.Wireless80211 or NetworkInterfaceType.Ethernet)
                {
                    priority = 0;
                }
                else if (IsPrivateRfc1918(address.Address))
                {
                    priority = 1;
                }

                candidates.Add((priority, address.Address.ToString()));
            }
        }

        return candidates
            .OrderBy(c => c.Priority)
            .Select(c => c.Address)
            .FirstOrDefault();
    }

    private static bool IsPrivateRfc1918(IPAddress address)
    {
        var bytes = address.GetAddressBytes();
        if (bytes.Length != 4)
        {
            return false;
        }

        return bytes[0] == 10
            || (bytes[0] == 172 && bytes[1] is >= 16 and <= 31)
            || (bytes[0] == 192 && bytes[1] == 168);
    }
}