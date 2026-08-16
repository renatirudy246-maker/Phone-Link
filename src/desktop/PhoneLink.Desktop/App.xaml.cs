using System.IO;
using System.Windows;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using PhoneLink.Core.Auth;
using PhoneLink.Core.Identity;
using PhoneLink.Core.Pairing;
using PhoneLink.Core.Security;
using PhoneLink.Core.Transfers;
using PhoneLink.Desktop.Networking;
using PhoneLink.Desktop.ViewModels;
using PhoneLink.Infrastructure.Auth;
using PhoneLink.Infrastructure.Crypto;
using PhoneLink.Infrastructure.Identity;
using PhoneLink.Infrastructure.Pairing;
using PhoneLink.Infrastructure.Paths;
using PhoneLink.Infrastructure.Storage;
using PhoneLink.Infrastructure.Transfers;
using PhoneLink.Transport.Discovery;
using PhoneLink.Transport.Hosting;
using Serilog;

namespace PhoneLink.Desktop;

public partial class App : System.Windows.Application
{
    private IHost? _host;
    private IReceiverHost? _receiver;
    private IMdnsAdvertiser? _mdns;
    private IUdpDiscoveryResponder? _udpResponder;
    private TrayIcon? _tray;
    private bool _exiting;

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        var paths = AppPaths.Default;
        var dataDir = Environment.GetEnvironmentVariable("PHONELINK_DATA_DIR");
        if (!string.IsNullOrWhiteSpace(dataDir))
        {
            paths = new AppPaths(dataDir);
        }

        Log.Logger = new LoggerConfiguration()
            .MinimumLevel.Information()
            .WriteTo.File(
                path: System.IO.Path.Combine(paths.LogsDir, "phonelink-.log"),
                rollingInterval: RollingInterval.Day,
                shared: true,
                flushToDiskInterval: TimeSpan.FromSeconds(2))
            .WriteTo.Debug()
            .CreateLogger();

        try
        {
            var lanIp = LanAddress.GetFirstIpv4() ?? "127.0.0.1";
            var receiverOptions = new ReceiverOptions();

            _host = Host.CreateDefaultBuilder()
                .UseSerilog()
                .ConfigureServices(services =>
                {
                    services.AddSingleton(paths);
                    services.AddSingleton<PhoneLinkDb>();
                    services.AddSingleton<IDeviceIdentityProvider, SqliteDeviceIdentityProvider>();
                    services.AddSingleton<IPairedDeviceRepository, PairedDeviceRepository>();
                    services.AddSingleton<IPairingSessionService>(sp =>
                        new PairingSessionService(
                            sp.GetRequiredService<PhoneLinkDb>(),
                            sp.GetRequiredService<IDeviceIdentityProvider>(),
                            sp.GetRequiredService<ITlsCertificateProvider>(),
                            lanIp,
                            receiverOptions.Port,
                            sp.GetRequiredService<ILogger<PairingSessionService>>()));
                    services.AddSingleton<ITokenValidator, PairedDeviceTokenValidator>();
                    services.AddSingleton<ITransferRepository, TransferRepository>();
                    services.AddSingleton<ITransferFileStore, TransferFileStore>();
                    services.AddSingleton<TransferEventBus>();
                    services.AddSingleton<ITransferEventSource>(sp => sp.GetRequiredService<TransferEventBus>());
                    services.AddSingleton<ITransferEventPublisher>(sp => sp.GetRequiredService<TransferEventBus>());
                    services.AddSingleton<ITransferService, TransferService>();
                    services.AddSingleton<ITlsCertificateProvider, CertificateStore>();
                    services.AddSingleton(receiverOptions);
                    services.AddSingleton<IReceiverHost, KestrelReceiverHost>();
                    services.AddSingleton<IMdnsAdvertiser, WindowsMdnsAdvertiser>();
                    services.AddSingleton<IUdpDiscoveryResponder, UdpDiscoveryResponder>();
                    services.AddSingleton<MainViewModel>();
                })
                .Build();

            await _host.StartAsync();
            _receiver = _host.Services.GetRequiredService<IReceiverHost>();
            await _receiver.StartAsync(CancellationToken.None);

            _udpResponder = _host.Services.GetRequiredService<IUdpDiscoveryResponder>();
            await _udpResponder.StartAsync(receiverOptions.Port, CancellationToken.None);

            var identity = await _host.Services.GetRequiredService<IDeviceIdentityProvider>()
                .GetIdentityAsync(CancellationToken.None);
            _mdns = _host.Services.GetRequiredService<IMdnsAdvertiser>();
            await _mdns.StartAsync(new MdnsAdvertisement(
                InstanceName: identity.DisplayName,
                Port: receiverOptions.Port,
                DeviceId: identity.DeviceId,
                DeviceName: identity.DisplayName), CancellationToken.None);

            var window = new MainWindow
            {
                DataContext = _host.Services.GetRequiredService<MainViewModel>(),
            };
            MainWindow = window;
            window.Show();

            _tray = new TrayIcon(
                _host.Services.GetRequiredService<MainViewModel>(),
                paths,
                exit: () =>
                {
                    if (MainWindow is MainWindow mainWindow)
                    {
                        mainWindow.AllowClose();
                    }

                    Shutdown();
                });

            // 测试专用钩子：仅当设置 PHONELINK_TEST_PAIRING_OUTPUT 时，
            // 创建一个真实 PairingSession 并把 QR payload 写入指定文件（供 smoke test 走真实配对流程）。
            // 不绕过任何认证逻辑，不构成生产认证后门。
            var testOutput = Environment.GetEnvironmentVariable("PHONELINK_TEST_PAIRING_OUTPUT");
            if (!string.IsNullOrWhiteSpace(testOutput))
            {
                var pairing = _host.Services.GetRequiredService<IPairingSessionService>();
                var created = await pairing.CreateAsync(CancellationToken.None);
                await File.WriteAllTextAsync(testOutput, created.QrPayload);
                Log.Information("Test pairing payload written to {Path}.", testOutput);
            }
        }
        catch (Exception ex)
        {
            Log.Fatal(ex, "Failed to start Phone-Link.");
            MessageBox.Show(
                $"Phone-Link 启动失败：{ex.Message}",
                "Phone-Link",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
            Shutdown(1);
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        Log.Information("Phone-Link exiting.");
        try
        {
            _tray?.Dispose();
            _tray = null;
            _udpResponder?.StopAsync(CancellationToken.None).GetAwaiter().GetResult();
            _mdns?.StopAsync(CancellationToken.None).GetAwaiter().GetResult();
            _receiver?.StopAsync(CancellationToken.None).GetAwaiter().GetResult();
            _host?.StopAsync(TimeSpan.FromSeconds(5)).GetAwaiter().GetResult();
        }
        catch (Exception ex)
        {
            Log.Error(ex, "Error during shutdown.");
        }
        finally
        {
            Log.CloseAndFlush();
        }

        base.OnExit(e);
    }
}