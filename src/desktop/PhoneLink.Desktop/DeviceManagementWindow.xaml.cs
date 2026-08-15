using System.Windows;
using System.Windows.Controls;
using PhoneLink.Desktop.ViewModels;

namespace PhoneLink.Desktop;

public partial class DeviceManagementWindow : Window
{
    public DeviceManagementWindow(MainViewModel vm)
    {
        InitializeComponent();
        DataContext = vm;
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

    private async void OnRestoreClick(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: string deviceId } || DataContext is not MainViewModel vm)
        {
            return;
        }

        try
        {
            await vm.RestoreTrustAsync(deviceId);
        }
        catch (Exception ex)
        {
            MessageBox.Show($"恢复信任失败：{ex.Message}", "Phone-Link", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void OnCloseClick(object sender, RoutedEventArgs e) => Close();
}