using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Threading;
using PhoneLink.Core;
using PhoneLink.Core.Models;
using PhoneLink.Core.Transfers;

namespace PhoneLink.Desktop.ViewModels;

public sealed class MainViewModel : INotifyPropertyChanged
{
    private readonly ITransferEventSource _events;
    private readonly ITransferRepository _repository;

    private string _statusLine = "正在初始化…";
    private string _latestImagePath = string.Empty;

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

    public ObservableCollection<RecentItem> Recent { get; } = [];

    public MainViewModel(ITransferEventSource events, ITransferRepository repository)
    {
        _events = events;
        _repository = repository;
        _events.Received += OnTransferReceived;
        _ = LoadRecentAsync();
    }

    private async Task LoadRecentAsync()
    {
        try
        {
            var records = await _repository.GetRecentAsync(20, CancellationToken.None);
            var dispatcher = Application.Current?.Dispatcher;
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
        var dispatcher = Application.Current?.Dispatcher;
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