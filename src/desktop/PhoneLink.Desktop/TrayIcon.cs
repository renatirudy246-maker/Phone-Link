using System.Drawing;
using System.IO;
using System.Windows;
using PhoneLink.Desktop.ViewModels;
using PhoneLink.Infrastructure.Paths;

namespace PhoneLink.Desktop;

/// <summary>
/// 最小系统托盘：Open Phone-Link / Pause Receiving / Pair New Phone / Open Inbox Folder / Exit。
/// 窗口关闭 → 隐藏到托盘（Receiver 继续运行）；托盘 Exit 才真正退出。
/// </summary>
public sealed class TrayIcon : IDisposable
{
    private readonly MainViewModel _viewModel;
    private readonly AppPaths _paths;
    private readonly System.Windows.Forms.NotifyIcon _icon;
    private readonly System.Windows.Forms.ContextMenuStrip _menu;
    private readonly System.Windows.Forms.ToolStripMenuItem _pauseItem;
    private readonly Action _exit;

    public TrayIcon(MainViewModel viewModel, AppPaths paths, Action exit)
    {
        _viewModel = viewModel;
        _paths = paths;
        _exit = exit;

        _pauseItem = new System.Windows.Forms.ToolStripMenuItem("暂停接收");
        _pauseItem.Click += (_, _) => TogglePause();

        _menu = new System.Windows.Forms.ContextMenuStrip();
        _menu.Items.Add("打开 Phone-Link", null, (_, _) => ShowMainWindow());
        _menu.Items.Add(_pauseItem);
        _menu.Items.Add("配对新手机", null, (_, _) => _viewModel.OpenPairing());
        _menu.Items.Add("打开收件文件夹", null, (_, _) => OpenInbox());
        _menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
        _menu.Items.Add("退出", null, (_, _) => _exit());

        _icon = new System.Windows.Forms.NotifyIcon
        {
            Icon = CreateIcon(),
            Text = "Phone-Link",
            Visible = true,
            ContextMenuStrip = _menu,
        };
        _icon.DoubleClick += (_, _) => ShowMainWindow();

        _viewModel.PauseChanged += OnPauseChanged;
        OnPauseChanged(this, _viewModel.IsPaused);
    }

    private void OnPauseChanged(object? sender, bool paused)
    {
        _pauseItem.Text = paused ? "恢复接收" : "暂停接收";
    }

    private void TogglePause() => _viewModel.TogglePause();

    private void ShowMainWindow()
    {
        var window = System.Windows.Application.Current?.MainWindow;
        if (window is null)
        {
            return;
        }

        window.Show();
        if (window.WindowState == WindowState.Minimized)
        {
            window.WindowState = WindowState.Normal;
        }

        window.Activate();
    }

    private void OpenInbox()
    {
        var inbox = _paths.InboxDir;
        Directory.CreateDirectory(inbox);
        System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("explorer.exe", inbox)
        {
            UseShellExecute = true,
        });
    }

    private static Icon CreateIcon()
    {
        var iconPath = Path.Combine(AppContext.BaseDirectory, "Assets", "app.ico");
        if (File.Exists(iconPath))
        {
            return new Icon(iconPath, 16, 16);
        }

        // Fallback: 图标资源缺失时使用最小绿点，保证托盘不空白。
        using var bitmap = new Bitmap(16, 16);
        using (var g = Graphics.FromImage(bitmap))
        {
            g.Clear(Color.Transparent);
            using var brush = new SolidBrush(Color.FromArgb(34, 197, 94));
            g.FillEllipse(brush, 1, 1, 14, 14);
            using var white = new SolidBrush(Color.White);
            g.FillEllipse(white, 5, 5, 6, 6);
        }

        return Icon.FromHandle(bitmap.GetHicon());
    }

    public void Dispose()
    {
        _viewModel.PauseChanged -= OnPauseChanged;
        _icon.Visible = false;
        _icon.Dispose();
        _menu.Dispose();
    }
}