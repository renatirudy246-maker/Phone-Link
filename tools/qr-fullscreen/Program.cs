using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;

// 全屏显示 QR 图片（黑底、居中、Esc 退出），供手机扫码验收。
// 用法: qr-fullscreen <pngPath>

if (args.Length < 1 || !File.Exists(args[0]))
{
    Console.Error.WriteLine("usage: qr-fullscreen <pngPath>");
    return 1;
}

var app = new Application();
var window = new Window
{
    Title = "Phone-Link Pairing QR",
    WindowStyle = WindowStyle.None,
    ResizeMode = ResizeMode.NoResize,
    WindowState = WindowState.Maximized,
    Background = Brushes.Black,
    Topmost = true,
    ShowInTaskbar = true,
};

var image = new Image
{
    Source = new BitmapImage(new Uri(Path.GetFullPath(args[0]))),
    Stretch = Stretch.Uniform,
};

window.Content = new Grid { Children = { image } };
window.KeyDown += (_, e) =>
{
    if (e.Key == Key.Escape)
    {
        window.Close();
    }
};
window.MouseDown += (_, _) => window.Close();

app.Run(window);
return 0;