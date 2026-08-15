using Microsoft.Extensions.Logging.Abstractions;
using PhoneLink.Infrastructure.Pairing;
using PhoneLink.Infrastructure.Paths;
using PhoneLink.Infrastructure.Storage;
using QRCoder;

// 实机验收工具：
//   qr-show show <payloadFile> [outputPng]   —— 把 QR payload 渲染为 PNG 并用默认查看器打开
//   qr-show devices <dataDir>                —— 列出已配对设备
//   qr-show revoke <dataDir> <deviceId>      —— 撤销设备（模拟桌面端 UI 撤销）
//   qr-show restore <dataDir> <deviceId>     —— 恢复设备信任

if (args.Length == 0)
{
    Console.WriteLine(Usage());
    return 1;
}

try
{
    return args[0] switch
    {
        "show" => Show(args),
        "verify" => Verify(args),
        "devices" => ListDevices(args),
        "revoke" => SetTrusted(args, revoked: true),
        "restore" => SetTrusted(args, revoked: false),
        "reset-cert" => ResetCert(),
        _ => Usage(),
    };
}
catch (Exception ex)
{
    Console.Error.WriteLine($"ERROR: {ex.Message}");
    return 1;
}

static int Show(string[] args)
{
    if (args.Length < 2)
    {
        return Usage();
    }

    var payload = File.ReadAllText(args[1]);
    var pngPath = args.Length > 2 ? args[2] : Path.Combine(Path.GetTempPath(), "phonelink-pair-qr.png");

    using var generator = new QRCodeGenerator();
    using var qrData = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
    using var qrCode = new PngByteQRCode(qrData);
    var png = qrCode.GetGraphic(12, drawQuietZones: true);
    File.WriteAllBytes(pngPath, png);
    Console.WriteLine($"QR PNG written: {pngPath}");
    Console.WriteLine($"payload (first 80): {payload[..Math.Min(80, payload.Length)]}...");

    System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(pngPath) { UseShellExecute = true });
    return 0;
}

static int Verify(string[] args)
{
    if (args.Length < 2)
    {
        return Usage();
    }

    var payload = File.ReadAllText(args[1]);
    var pngPath = Path.GetTempFileName() + ".png";

    using var generator = new QRCodeGenerator();
    using var qrData = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
    using var qrCode = new PngByteQRCode(qrData);
    File.WriteAllBytes(pngPath, qrCode.GetGraphic(6, drawQuietZones: true));

    var reader = new ZXing.BarcodeReaderGeneric
    {
        Options = new ZXing.Common.DecodingOptions
        {
            TryHarder = true,
            PossibleFormats = [ZXing.BarcodeFormat.QR_CODE],
        },
    };
    using var bmp = new System.Drawing.Bitmap(pngPath);
    var source = new ZXing.RGBLuminanceSource(
        ToArgbBytes(bmp), bmp.Width, bmp.Height, ZXing.RGBLuminanceSource.BitmapFormat.ARGB32);
    var result = reader.Decode(source);
    var decoded = result?.Text ?? string.Empty;
    Console.WriteLine($"QR PNG self-check: decoded={decoded.Length} chars, expected={payload.Length} chars");
    Console.WriteLine(decoded == payload ? "MATCH: PNG contains the full payload." : "MISMATCH: PNG content differs!");
    File.Delete(pngPath);
    return decoded == payload ? 0 : 1;
}

static byte[] ToArgbBytes(System.Drawing.Bitmap bmp)
{
    var bytes = new byte[bmp.Width * bmp.Height * 4];
    int i = 0;
    for (int y = 0; y < bmp.Height; y++)
    {
        for (int x = 0; x < bmp.Width; x++)
        {
            var c = bmp.GetPixel(x, y);
            bytes[i++] = c.A;
            bytes[i++] = c.R;
            bytes[i++] = c.G;
            bytes[i++] = c.B;
        }
    }

    return bytes;
}

static int ResetCert()
{
    using var store = new System.Security.Cryptography.X509Certificates.X509Store(
        "My", System.Security.Cryptography.X509Certificates.StoreLocation.CurrentUser);
    store.Open(System.Security.Cryptography.X509Certificates.OpenFlags.ReadWrite);
    var found = store.Certificates
        .Find(
            System.Security.Cryptography.X509Certificates.X509FindType.FindBySubjectName,
            "PhoneLink-Desktop",
            validOnly: false)
        .OfType<System.Security.Cryptography.X509Certificates.X509Certificate2>()
        .ToList();
    foreach (var cert in found)
    {
        store.Remove(cert);
    }

    Console.WriteLine($"removed {found.Count} PhoneLink-Desktop certificate(s)");
    return 0;
}

static int ListDevices(string[] args)
{
    if (args.Length < 2)
    {
        return Usage();
    }

    var paths = new AppPaths(args[1]);
    var db = new PhoneLinkDb(paths);
    var repo = new PairedDeviceRepository(db, NullLogger<PairedDeviceRepository>.Instance);
    var devices = repo.ListAllAsync(CancellationToken.None).GetAwaiter().GetResult();
    Console.WriteLine($"paired devices: {devices.Count}");
    foreach (var device in devices)
    {
        Console.WriteLine(
            $"  {device.DeviceId} | {device.DisplayName} | {device.Platform} | trusted={device.IsTrusted} | lastSeen={device.LastSeenAt:O}");
    }

    return 0;
}

static int SetTrusted(string[] args, bool revoked)
{
    if (args.Length < 3)
    {
        return Usage();
    }

    var paths = new AppPaths(args[1]);
    var db = new PhoneLinkDb(paths);
    var repo = new PairedDeviceRepository(db, NullLogger<PairedDeviceRepository>.Instance);
    repo.SetRevokedAsync(args[2], revoked, CancellationToken.None).GetAwaiter().GetResult();
    Console.WriteLine($"device {args[2]} -> revoked={revoked}");
    return 0;
}

static int Usage()
{
    Console.WriteLine("""
        Usage:
          qr-show show <payloadFile> [outputPng]
          qr-show verify <payloadFile>
          qr-show devices <dataDir>
          qr-show revoke <dataDir> <deviceId>
          qr-show restore <dataDir> <deviceId>
          qr-show reset-cert
        """);
    return 1;
}