using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using PhoneLink.Desktop.ViewModels;

namespace PhoneLink.Desktop;

public partial class MainWindow : Window
{
    private bool _allowClose;
    private ScrollViewer? _filmstripScrollViewer;
    private bool _syncingSlider;

    public MainWindow()
    {
        InitializeComponent();
        DataContextChanged += OnDataContextChanged;
        Closing += OnWindowClosing;
        Loaded += OnWindowLoaded;
    }

    private void OnWindowLoaded(object sender, RoutedEventArgs e)
    {
        _filmstripScrollViewer = FindScrollViewer(RecentListBox);
        if (_filmstripScrollViewer is not null)
        {
            _filmstripScrollViewer.ScrollChanged += OnFilmstripScrollChanged;
        }

        SyncSliderFromViewer();
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

    private void OnManageDevicesClick(object sender, RoutedEventArgs e)
    {
        if (DataContext is not MainViewModel vm)
        {
            return;
        }

        var window = new DeviceManagementWindow(vm)
        {
            Owner = this,
        };
        window.ShowDialog();
    }

    private void OnPairNewPhoneClick(object sender, RoutedEventArgs e)
    {
        if (DataContext is MainViewModel vm)
        {
            vm.OpenPairing();
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

    /// <summary>双击 Latest 图片直接打开（系统默认查看器）。</summary>
    private void OnLatestImageDoubleClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (e.ChangedButton != System.Windows.Input.MouseButton.Left || e.ClickCount < 2)
        {
            return;
        }

        if (DataContext is MainViewModel vm && vm.CanOpenLatest)
        {
            vm.OpenLatest();
        }
    }

    /// <summary>滚轮在 Filmstrip 上时直接水平滚动（无需 Shift），向上→左、向下→右。</summary>
    private void OnFilmstripPreviewMouseWheel(object sender, MouseWheelEventArgs e)
    {
        var scrollViewer = _filmstripScrollViewer;
        if (scrollViewer is null || scrollViewer.ScrollableWidth <= 0)
        {
            return;
        }

        // 像素滚动（CanContentScroll=False）：每次滚轮移动约 2 个 thumbnail 宽
        var delta = (e.Delta > 0 ? -1 : 1) * 2 * TilePitch;
        scrollViewer.ScrollToHorizontalOffset(scrollViewer.HorizontalOffset + delta);
        e.Handled = true;
    }

    /// <summary>内部 ScrollViewer 滚动 → 底部位置滑条同步（Maximum/Value）。</summary>
    private void OnFilmstripScrollChanged(object? sender, ScrollChangedEventArgs e)
    {
        SyncSliderFromViewer();
    }

    /// <summary>拖动位置滑条 → 内部 ScrollViewer 像素级滚动（Value 与 HorizontalOffset 直接 1:1）。</summary>
    private void OnFilmstripSliderValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_syncingSlider || _filmstripScrollViewer is not { } scrollViewer)
        {
            return;
        }

        scrollViewer.ScrollToHorizontalOffset(e.NewValue);
    }

    private void SyncSliderFromViewer()
    {
        if (_filmstripScrollViewer is not { } scrollViewer)
        {
            return;
        }

        var scrollable = scrollViewer.ScrollableWidth;
        RecentPositionSlider.Visibility = scrollable > 0
            ? Visibility.Visible
            : Visibility.Collapsed;

        _syncingSlider = true;
        RecentPositionSlider.Maximum = scrollable;
        RecentPositionSlider.Value = scrollViewer.HorizontalOffset;
        _syncingSlider = false;
    }

    /// <summary>选中变化（点击/新图成为 Latest）时确保选中 Tile 完全滚入可视区。</summary>
    private void OnRecentSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (RecentListBox.SelectedItem is { } selected)
        {
            RecentListBox.ScrollIntoView(selected);
        }
    }

    private const double TilePitch = 148; // tile 宽 132 + 左右 padding 16

    private static ScrollViewer? FindScrollViewer(DependencyObject root)
    {
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
        {
            var child = VisualTreeHelper.GetChild(root, i);
            if (child is ScrollViewer scrollViewer)
            {
                return scrollViewer;
            }

            var nested = FindScrollViewer(child);
            if (nested is not null)
            {
                return nested;
            }
        }

        return null;
    }

    private void StatusText(string message)
    {
        if (DataContext is MainViewModel vm)
        {
            vm.SetTransientStatus(message);
        }
    }
}