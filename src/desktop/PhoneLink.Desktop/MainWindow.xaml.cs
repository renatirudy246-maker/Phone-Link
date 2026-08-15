using System.Windows;
using System.Windows.Controls;
using PhoneLink.Desktop.ViewModels;

namespace PhoneLink.Desktop;

public partial class MainWindow : Window
{
    private bool _allowClose;

    public MainWindow()
    {
        InitializeComponent();
        DataContextChanged += OnDataContextChanged;
        Closing += OnWindowClosing;
    }

    /// <summary>窗口关闭默认隐藏到托盘（Receiver 继续运行）；App.Shutdown 时才真正关闭。</summary>
    private void OnWindowClosing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        if (_allowClose)
        {
            return;
        }

        e.Cancel = true;
        Hide();
        if (DataContext is MainViewModel vm)
        {
            vm.SetTransientStatus("已最小化到托盘，接收继续运行。右键托盘图标可退出。");
        }
    }

    /// <summary>托盘 Exit 调用（App 退出前放开关闭）。</summary>
    public void AllowClose() => _allowClose = true;

    private void OnDataContextChanged(object sender, DependencyPropertyChangedEventArgs e)
    {
        if (e.OldValue is MainViewModel old)
        {
            old.PairingRequested -= OnPairingRequested;
        }

        if (e.NewValue is MainViewModel vm)
        {
            vm.PairingRequested += OnPairingRequested;
        }
    }

    private void OnPairingRequested(object? sender, PairingWindowViewModel pairing)
    {
        var window = new PairingWindow(pairing)
        {
            Owner = this,
        };
        window.Show();
    }

    private void OnPairNewPhoneClick(object sender, RoutedEventArgs e)
    {
        if (DataContext is MainViewModel vm)
        {
            vm.OpenPairing();
        }
    }

    private async void OnRevokeClick(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: string deviceId } || DataContext is not MainViewModel vm)
        {
            return;
        }

        var confirm = MessageBox.Show(
            $"撤销信任设备 {deviceId}？该设备下一次请求将立即被拒绝。",
            "撤销设备",
            MessageBoxButton.YesNo,
            MessageBoxImage.Warning);
        if (confirm != MessageBoxResult.Yes)
        {
            return;
        }

        try
        {
            await vm.RevokeAsync(deviceId);
        }
        catch (Exception ex)
        {
            MessageBox.Show($"撤销失败：{ex.Message}", "Phone-Link", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void OnOpenLatestClick(object sender, RoutedEventArgs e)
    {
        if (DataContext is MainViewModel vm)
        {
            vm.OpenLatest();
        }
    }

    private void OnCopyLatestClick(object sender, RoutedEventArgs e)
    {
        if (DataContext is not MainViewModel vm)
        {
            return;
        }

        if (vm.CopyLatest())
        {
            StatusText("图片已复制到剪贴板，可直接 Ctrl+V 粘贴。");
        }
        else
        {
            MessageBox.Show("复制失败：图片文件不可用。", "Phone-Link", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void OnOpenFolderClick(object sender, RoutedEventArgs e)
    {
        if (DataContext is MainViewModel vm)
        {
            vm.OpenLatestFolder();
        }
    }

    private void OnTogglePauseClick(object sender, RoutedEventArgs e)
    {
        if (DataContext is MainViewModel vm)
        {
            vm.TogglePause();
        }
    }

    private void StatusText(string message)
    {
        if (DataContext is MainViewModel vm)
        {
            vm.SetTransientStatus(message);
        }
    }
}