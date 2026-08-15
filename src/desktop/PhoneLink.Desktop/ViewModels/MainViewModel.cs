using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows;

namespace PhoneLink.Desktop.ViewModels;

public sealed class MainViewModel : INotifyPropertyChanged
{
    private string _statusLine = "正在初始化…";

    public string StatusLine
    {
        get => _statusLine;
        private set => SetField(ref _statusLine, value);
    }

    public string DeviceId { get; } = $"desktop-{Environment.MachineName}";

    public MainViewModel()
    {
        StatusLine = "接收服务未启动（Phase 1 启用）";
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