using System.Windows;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using PhoneLink.Core.Auth;
using PhoneLink.Core.Identity;
using PhoneLink.Core.Security;
using PhoneLink.Core.Transfers;
using PhoneLink.Desktop.ViewModels;
using PhoneLink.Infrastructure.Auth;
using PhoneLink.Infrastructure.Crypto;
using PhoneLink.Infrastructure.Identity;
using PhoneLink.Infrastructure.Paths;
using PhoneLink.Infrastructure.Storage;
using PhoneLink.Infrastructure.Transfers;
using PhoneLink.Transport.Hosting;
using Serilog;

namespace PhoneLink.Desktop;

public partial class App : Application
{
    private IHost? _host;
    private IReceiverHost? _receiver;

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
            _host = Host.CreateDefaultBuilder()
                .UseSerilog()
                .ConfigureServices(services =>
                {
                    services.AddSingleton(paths);
                    services.AddSingleton<PhoneLinkDb>();
                    services.AddSingleton<IDeviceIdentityProvider, SqliteDeviceIdentityProvider>();
                    services.AddSingleton<DevTokenStore>();
                    services.AddSingleton<ITokenValidator, DevTokenValidator>();
                    services.AddSingleton<ITransferRepository, TransferRepository>();
                    services.AddSingleton<ITransferFileStore, TransferFileStore>();
                    services.AddSingleton<TransferEventBus>();
                    services.AddSingleton<ITransferEventSource>(sp => sp.GetRequiredService<TransferEventBus>());
                    services.AddSingleton<ITransferEventPublisher>(sp => sp.GetRequiredService<TransferEventBus>());
                    services.AddSingleton<ITransferService, TransferService>();
                    services.AddSingleton<ITlsCertificateProvider, CertificateStore>();
                    services.AddSingleton(new ReceiverOptions());
                    services.AddSingleton<IReceiverHost, KestrelReceiverHost>();
                    services.AddSingleton<MainViewModel>();
                })
                .Build();

            await _host.StartAsync();
            _receiver = _host.Services.GetRequiredService<IReceiverHost>();
            await _receiver.StartAsync(CancellationToken.None);

            var window = new MainWindow
            {
                DataContext = _host.Services.GetRequiredService<MainViewModel>(),
            };
            MainWindow = window;
            window.Show();
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