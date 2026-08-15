using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Threading;
using PhoneLink.Core;
using PhoneLink.Core.Models;
using PhoneLink.Core.Pairing;
using PhoneLink.Core.Transfers;
using PhoneLink.Desktop.Commands;
using PhoneLink.Transport.Hosting;

namespace PhoneLink.Desktop.ViewModels;

public sealed class MainViewModel : INotifyPropertyChanged
{
    private readonly ITransferEventSource _events;
    private readonly ITransferRepository _repository;
    private readonly IPairedDeviceRepository _deviceRepository;
    private readonly IPairingSessionService _pairingSessionService;
    private readonly IReceiverHost _receiver;
    private readonly DispatcherTimer _refreshTimer;

    private string _statusLine = "正在初始化…";
    private string _latestImagePath = string.Empty;
    private bool _isPaused;

    public string StatusLine
    {
        get => _statusLine;
        private set => SetField(ref _statusLine, value);
    }

    public string LatestImagePath
    {
        get => _latestImagePath;
        private set => SetField(ref _latestImagePath, value);
    }

public bool IsPaused
    {
        get => _isPaused;
        private set
        {
            var changed = !EqualityComparer<bool>.Default.Equals(_isPaused, value);
            _isPaused = value;
            if (changed)
            {
                PauseChanged?.Invoke(this, value);
            }
        }
    }

    public ObservableCollection<RecentItem> Recent { get; } = [];

    public ObservableCollection<DeviceItem> Devices { get; } = [];

    public event EventHandler<bool>? PauseChanged;

    public event EventHandler<PairingWindowViewModel>? PairingRequested;

    public MainViewModel(
        ITransferEventSource events,
        ITransferRepository repository,
        IPairedDeviceRepository deviceRepository,
        IPairingSessionService pairingSessionService,
        IReceiverHost receiver)
    {
        _events = events;
        _repository = repository;
        _deviceRepository = deviceRepository;
        _pairingSessionService = pairingSessionService;
        _receiver = receiver;
        _events.Received += OnTransferReceived;
        _ = LoadRecentAsync();
        _ = RefreshDevicesAsync();

        _refreshTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(5) };
        _refreshTimer.Tick += async (_, _) => await RefreshDevicesAsync();
        _refreshTimer.Start();
    }

    public void TogglePause()
    {
        if (_receiver.IsPaused)
        {
            _receiver.Resume();
            StatusLine = "接收中 · 已恢复接收";
        }
        else
        {
            _receiver.Pause();
            StatusLine = "已暂停接收 · 手机发送将收到 SERVICE_PAUSED";
        }

        IsPaused = _receiver.IsPaused;
    }

    public bool CanOpenLatest => LatestActions.IsValidImagePath(LatestImagePath);

    public void OpenLatest() => LatestActions.Open(LatestImagePath);

    public void OpenLatestFolder() => LatestActions.OpenFolder(LatestImagePath);

    public bool CopyLatest() => LatestActions.CopyImage(LatestImagePath);

    public void SetTransientStatus(string message) => StatusLine = message;

    public void OpenPairing()
    {
        var window = new PairingWindowViewModel(_pairingSessionService);
        PairingRequested?.Invoke(this, window);
    }

    public async Task RevokeAsync(string deviceId)
    {
        await _deviceRepository.SetRevokedAsync(deviceId, revoked: true, CancellationToken.None);
        await RefreshDevicesAsync();
    }

    private async Task RefreshDevicesAsync()
    {
        try
        {
            var devices = await _deviceRepository.ListAllAsync(CancellationToken.None);
            var dispatcher = System.Windows.Application.Current?.Dispatcher;
            if (dispatcher is null)
            {
                return;
            }

            await dispatcher.InvokeAsync(() =>
            {
                Devices.Clear();
                foreach (var device in devices)
                {
                    Devices.Add(DeviceItem.From(device));
                }
            });
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Refresh devices failed: {ex.Message}");
        }
    }

    private async Task LoadRecentAsync()
    {
        try
        {
            var records = await _repository.GetRecentAsync(20, CancellationToken.None);
            var dispatcher = System.Windows.Application.Current?.Dispatcher;
            if (dispatcher is null)
            {
                return;
            }

            await dispatcher.InvokeAsync(() =>
            {
                Recent.Clear();
                foreach (var record in records)
                {
                    if (record.Status == TransferStatus.Completed)
                    {
                        Recent.Add(RecentItem.From(record));
                    }
                }

                var latest = records.FirstOrDefault(r => r.Status == TransferStatus.Completed);
                if (latest is not null)
                {
                    LatestImagePath = latest.LocalFilePath;
                }

                StatusLine = $"接收中 · {records.Count} 条历史记录";
            });
        }
        catch (Exception ex)
        {
            StatusLine = $"历史记录加载失败：{ex.Message}";
        }
    }

    private void OnTransferReceived(object? sender, TransferRecord record)
    {
        var dispatcher = System.Windows.Application.Current?.Dispatcher;
        if (dispatcher is null)
        {
            return;
        }

        dispatcher.BeginInvoke(DispatcherPriority.Background, () =>
        {
            if (record.Status != TransferStatus.Completed)
            {
                return;
            }

            Recent.Insert(0, RecentItem.From(record));
            LatestImagePath = record.LocalFilePath;
            StatusLine = $"接收中 · {DateTimeOffset.Now:HH:mm:ss} 收到 {record.OriginalFileName}";
        });
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private void SetField<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value))
        {
            return;
        }

        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}

public sealed record RecentItem(string TimeLabel, string FileName, string StatusLabel)
{
    public static RecentItem From(TransferRecord record)
        => new(
            record.ReceivedAt.ToLocalTime().ToString("HH:mm:ss"),
            record.OriginalFileName,
            record.Status == TransferStatus.Completed ? "已接收" : record.ErrorCode ?? record.Status.ToString());
}

public sealed record DeviceItem(
    string DeviceId,
    string DisplayName,
    string Platform,
    string LastSeenLabel,
    string StatusLabel,
    bool IsTrusted,
    string StatusColor)
{
    public static DeviceItem From(PairedDevice device)
    {
        var lastSeen = device.LastSeenAt?.ToLocalTime().ToString("MM-dd HH:mm") ?? "从未";
        var trusted = device.IsTrusted;
        return new DeviceItem(
            device.DeviceId,
            device.DisplayName,
            device.Platform,
            lastSeen,
            trusted ? "已信任" : "已撤销",
            trusted,
            trusted ? "#4CAF50" : "#C62828");
    }
}