namespace PhoneLink.Core;

public static class AppInfo
{
    public const string AppName = "Phone-Link";
    public const int ProtocolVersion = 1;
    public const string ServiceType = "_phonelink._tcp.local";
    public const long MaxImageSizeBytes = 25 * 1024 * 1024;

    public static readonly string[] SupportedImageMimeTypes =
    [
        "image/jpeg",
        "image/png",
        "image/webp",
    ];
}