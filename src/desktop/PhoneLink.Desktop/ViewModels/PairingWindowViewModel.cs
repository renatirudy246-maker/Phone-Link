using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using PhoneLink.Core.Pairing;

namespace PhoneLink.Desktop.ViewModels;

/// <summary>
/// 配对弹窗 ViewModel：生成 PairingSession + QR payload，渲染为 BitmapSource。
/// 每 15 秒轮换一次 session（旧 session 过期作废），显示剩余有效期。
/// </summary>
public sealed class PairingWindowViewModel : INotifyPropertyChanged, IDisposable
{
    private readonly IPairingSessionService _pairingSessionService;
    private readonly DispatcherTimer _rotateTimer;
    private readonly DispatcherTimer _countdownTimer;

    private string _qrPayload = string.Empty;
    private BitmapSource? _qrImage;
    private string _sessionId = string.Empty;
    private string _expiryText = string.Empty;
    private string _errorText = string.Empty;
    private bool _disposed;

    public string QrPayload
    {
        get => _qrPayload;
        private set => SetField(ref _qrPayload, value);
    }

    public BitmapSource? QrImage
    {
        get => _qrImage;
        private set => SetField(ref _qrImage, value);
    }

    public string SessionId
    {
        get => _sessionId;
        private set => SetField(ref _sessionId, value);
    }

    public string ExpiryText
    {
        get => _expiryText;
        private set => SetField(ref _expiryText, value);
    }

    public string ErrorText
    {
        get => _errorText;
        private set => SetField(ref _errorText, value);
    }

    private DateTimeOffset _expiresAt;

    public PairingWindowViewModel(IPairingSessionService pairingSessionService)
    {
        _pairingSessionService = pairingSessionService;
        _rotateTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(15) };
        _rotateTimer.Tick += async (_, _) => await RotateAsync();
        _rotateTimer.Start();

        _countdownTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _countdownTimer.Tick += (_, _) => UpdateExpiryText();
        _countdownTimer.Start();

        _ = RotateAsync();
    }

    private async Task RotateAsync()
    {
        if (_disposed)
        {
            return;
        }

        try
        {
            var created = await _pairingSessionService.CreateAsync(CancellationToken.None);
            QrPayload = created.QrPayload;
            SessionId = PhoneLink.Core.Ids.Short(created.Session.SessionId);
            _expiresAt = created.Session.ExpiresAt;
            QrImage = QrCodeRenderer.Render(created.QrPayload);
            ErrorText = string.Empty;
            UpdateExpiryText();
        }
        catch (Exception ex)
        {
            ErrorText = $"生成二维码失败：{ex.Message}";
        }
    }

    private void UpdateExpiryText()
    {
        if (_expiresAt == default)
        {
            ExpiryText = string.Empty;
            return;
        }

        var remaining = _expiresAt - DateTimeOffset.UtcNow;
        ExpiryText = remaining > TimeSpan.Zero
            ? $"二维码有效期 {Math.Ceiling(remaining.TotalSeconds)} 秒"
            : "二维码已过期，等待刷新…";
    }

    public void Dispose()
    {
        _disposed = true;
        _rotateTimer.Stop();
        _countdownTimer.Stop();
        _rotateTimer.Tick -= async (_, _) => await RotateAsync();
        _countdownTimer.Tick -= (_, _) => UpdateExpiryText();
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

public static class QrCodeRenderer
{
    public static BitmapSource Render(string payload, int pixelsPerModule = 8)
    {
        using var generator = new QRCoder.QRCodeGenerator();
        var qrData = generator.CreateQrCode(payload, QRCoder.QRCodeGenerator.ECCLevel.M);
        using var code = new QRCoder.BitmapByteQRCode(qrData);
        var bytes = code.GetGraphic(pixelsPerModule);

        using var stream = new System.IO.MemoryStream(bytes);
        var bitmap = new BitmapImage();
        bitmap.BeginInit();
        bitmap.CacheOption = BitmapCacheOption.OnLoad;
        bitmap.StreamSource = stream;
        bitmap.EndInit();
        bitmap.Freeze();
        return bitmap;
    }
}