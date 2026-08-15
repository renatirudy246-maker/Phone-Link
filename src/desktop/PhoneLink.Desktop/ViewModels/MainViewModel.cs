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
    private readonly Dictionary<string, string> _deviceNames = new();

    private string _statusLine = "等待手机连接";
    private string _latestImagePath = string.Empty;
    private bool _isPaused;
    private RecentItem? _selectedRecent;
    private DeviceItem? _currentDevice;
    private readonly DispatcherTimer _statusResetTimer;

    public string StatusLine
    {
        get => _statusLine;
        private set => SetField(ref _statusLine, value);
    }

    /// <summary>顶部状态点颜色：接收中（绿）/ 已暂停或无设备（灰）/ 已撤销（红）。</summary>
    public string StatusDotColor
    {
        get
        {
            if (IsPaused)
            {
                return "#9CA3AF";
            }

            if (CurrentDevice is null)
            {
                return "#9CA3AF";
            }

            return CurrentDevice.IsTrusted ? "#22C55E" : "#C62828";
        }
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
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(StatusDotColor)));
            }
        }
    }

    public ObservableCollection<RecentItem> Recent { get; } = [];

    public ObservableCollection<DeviceItem> Devices { get; } = [];

    /// <summary>当前设备：最近在线（优先已信任）的那台，主界面只展示这一台。</summary>
    public DeviceItem? CurrentDevice
    {
        get => _currentDevice;
        private set
        {
            if (!SetField(ref _currentDevice, value))
            {
                return;
            }

            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(StatusDotColor)));
            if (!_isPaused && !_statusResetTimer.IsEnabled)
            {
                UpdateStatusFromDevice();
            }
        }
    }

    /// <summary>Recent 列表当前选中项：点击即切换 Latest 大图。</summary>
    public RecentItem? SelectedRecent
    {
        get => _selectedRecent;
        set
        {
            if (!SetField(ref _selectedRecent, value) || value is null)
            {
                return;
            }

            if (File.Exists(value.LocalFilePath))
            {
                LatestImagePath = value.LocalFilePath;
                SetTransientStatus($"正在显示 {value.TimeLabel} 收到的图片");
            }
        }
    }

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

        _statusResetTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };
        _statusResetTimer.Tick += (_, _) =>
        {
            _statusResetTimer.Stop();
            UpdateStatusFromDevice();
        };
    }

    /// <summary>状态行回到"接收中 · 设备名"基线（无设备则等待手机连接）。</summary>
    private void UpdateStatusFromDevice()
    {
        StatusLine = CurrentDevice is { IsTrusted: true } device
            ? $"接收中 · {device.DisplayName}"
            : "等待手机连接";
    }

    public void TogglePause()
    {
        if (_receiver.IsPaused)
        {
            _receiver.Resume();
            IsPaused = _receiver.IsPaused;
            UpdateStatusFromDevice();
        }
        else
        {
            _receiver.Pause();
            IsPaused = _receiver.IsPaused;
            StatusLine = "已暂停接收";
        }
    }

    public bool CanOpenLatest => LatestActions.IsValidImagePath(LatestImagePath);

    public void OpenLatest() => LatestActions.Open(LatestImagePath);

    public void OpenLatestFolder() => LatestActions.OpenFolder(LatestImagePath);

    public bool CopyLatest() => LatestActions.CopyImage(LatestImagePath);

    public void SetTransientStatus(string message)
    {
        StatusLine = message;
        _statusResetTimer.Stop();
        _statusResetTimer.Start();
    }

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

    public async Task RestoreTrustAsync(string deviceId)
    {
        await _deviceRepository.SetRevokedAsync(deviceId, revoked: false, CancellationToken.None);
        await RefreshDevicesAsync();
    }

    private async Task RefreshDevicesAsync()
    {
        try
        {
            var devices = await _deviceRepository.ListAllAsync(CancellationToken.None);
            foreach (var device in devices)
            {
                _deviceNames[device.DeviceId] = device.DisplayName;
            }

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

                CurrentDevice = DeviceItem.SelectCurrent(Devices);
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
                        Recent.Add(ToRecentItem(record));
                    }
                }

                var latest = records.FirstOrDefault(r => r.Status == TransferStatus.Completed);
                if (latest is not null)
                {
                    LatestImagePath = latest.LocalFilePath;
                }
            });
        }
        catch (Exception ex)
        {
            StatusLine = $"历史记录加载失败：{ex.Message}";
        }
    }

    private RecentItem ToRecentItem(TransferRecord record)
    {
        _deviceNames.TryGetValue(record.SenderDeviceId, out var deviceName);
        return RecentItem.From(record, deviceName ?? record.SenderDeviceId[..Math.Min(8, record.SenderDeviceId.Length)]);
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

            Recent.Insert(0, ToRecentItem(record));
            LatestImagePath = record.LocalFilePath;
            StatusLine = $"已收到 {record.OriginalFileName}";
            _statusResetTimer.Stop();
            _statusResetTimer.Start();
        });
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private bool SetField<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value))
        {
            return false;
        }

        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
        return true;
    }
}

public sealed record RecentItem(
    string TimeLabel,
    string FileName,
    string DeviceName,
    string LocalFilePath,
    string StatusLabel)
{
    public static RecentItem From(TransferRecord record, string deviceName)
        => new(
            record.ReceivedAt.ToLocalTime().ToString("HH:mm:ss"),
            record.OriginalFileName,
            deviceName,
            record.LocalFilePath,
            record.Status == TransferStatus.Completed ? "已接收" : record.ErrorCode ?? record.Status.ToString());
}

public sealed record DeviceItem(
    string DeviceId,
    string DisplayName,
    string Platform,
    string LastSeenLabel,
    long LastSeenTicks,
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
            device.LastSeenAt?.UtcTicks ?? 0L,
            trusted ? "已信任" : "已撤销",
            trusted,
            trusted ? "#4CAF50" : "#C62828");
    }

    /// <summary>主界面只展示一台"当前设备"：优先最近在线的已信任设备。</summary>
    public static DeviceItem? SelectCurrent(IEnumerable<DeviceItem> items)
        => items
            .OrderByDescending(d => d.IsTrusted)
            .ThenByDescending(d => d.LastSeenTicks)
            .FirstOrDefault();
}