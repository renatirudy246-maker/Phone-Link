namespace PhoneLink.Infrastructure.Paths;

/// <summary>
/// 本地数据目录（默认 %LOCALAPPDATA%\PhoneLink）。测试可注入临时目录。
/// </summary>
public sealed class AppPaths
{
    public string BaseDir { get; }
    public string DataDir { get; }
    public string InboxDir { get; }
    public string ThumbnailsDir { get; }
    public string LogsDir { get; }
    public string TempDir { get; }
    public string ScannerFeedbackDir { get; }

    public string DbPath => Path.Combine(DataDir, "phonelink.db");
    public string DevTokenPath => Path.Combine(DataDir, "dev-token.txt");

    public static AppPaths Default { get; } = new(
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PhoneLink"));

    public AppPaths(string baseDir)
    {
        BaseDir = Path.GetFullPath(baseDir);
        DataDir = Path.Combine(BaseDir, "data");
        InboxDir = Path.Combine(BaseDir, "inbox");
        ThumbnailsDir = Path.Combine(BaseDir, "thumbnails");
        LogsDir = Path.Combine(BaseDir, "logs");
        TempDir = Path.Combine(BaseDir, "temp");
        ScannerFeedbackDir = Path.Combine(BaseDir, "scanner-feedback");
        EnsureCreated();
    }

    private void EnsureCreated()
    {
        Directory.CreateDirectory(DataDir);
        Directory.CreateDirectory(InboxDir);
        Directory.CreateDirectory(ThumbnailsDir);
        Directory.CreateDirectory(LogsDir);
        Directory.CreateDirectory(TempDir);
        Directory.CreateDirectory(ScannerFeedbackDir);
    }
}