using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Media.Imaging;

namespace PhoneLink.Desktop.Commands;

/// <summary>
/// Latest 图片操作（Open / Copy / Open Folder）的纯逻辑封装，
/// 路径校验与 explorer 参数构造可单元测试。
/// </summary>
public static class LatestActions
{
    public static bool IsValidImagePath(string? path)
        => !string.IsNullOrWhiteSpace(path) && File.Exists(path);

    /// <summary>explorer /select 参数（路径含空格/逗号时用引号包裹）。</summary>
    public static string BuildExplorerSelectArgument(string filePath)
        => $"/select, \"{filePath}\"";

    public static void Open(string? filePath)
    {
        if (!IsValidImagePath(filePath))
        {
            return;
        }

        Process.Start(new ProcessStartInfo(filePath!) { UseShellExecute = true });
    }

    public static void OpenFolder(string? filePath)
    {
        if (!IsValidImagePath(filePath))
        {
            return;
        }

        Process.Start(new ProcessStartInfo("explorer.exe", BuildExplorerSelectArgument(filePath!))
        {
            UseShellExecute = true,
        });
    }

    /// <summary>图片复制到 Windows 剪贴板（供 Ctrl+V 粘贴到其他应用）。</summary>
    public static bool CopyImage(string? filePath)
    {
        if (!IsValidImagePath(filePath))
        {
            return false;
        }

        try
        {
            using var stream = new FileStream(filePath!, FileMode.Open, FileAccess.Read, FileShare.Read);
            var image = BitmapFrame.Create(
                stream,
                BitmapCreateOptions.None,
                BitmapCacheOption.OnLoad);
            Clipboard.SetImage(image);
            return true;
        }
        catch
        {
            return false;
        }
    }
}