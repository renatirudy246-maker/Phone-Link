using System.Windows;
using System.Windows.Controls;
using PhoneLink.Desktop.ViewModels;

namespace PhoneLink.Desktop;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        DataContextChanged += OnDataContextChanged;
    }

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
}