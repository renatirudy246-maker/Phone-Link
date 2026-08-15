using PhoneLink.Core;

namespace PhoneLink.Transport.Hosting;

public sealed class ReceiverOptions
{
    public int Port { get; init; } = 8484;
    public long MaxImageSizeBytes { get; init; } = AppInfo.MaxImageSizeBytes;
}