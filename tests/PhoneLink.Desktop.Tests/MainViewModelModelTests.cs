using PhoneLink.Core.Models;
using PhoneLink.Desktop.ViewModels;

namespace PhoneLink.Desktop.Tests;

public class MainViewModelModelTests
{
    private static TransferRecord CompletedRecord(
        string transferId = "t-1",
        string fileName = "photo.jpg",
        string localPath = @"C:\inbox\photo.jpg")
        => new(
            TransferId: transferId,
            SenderDeviceId: "mobile-00000000000000000000000000000000",
            OriginalFileName: fileName,
            MimeType: "image/jpeg",
            FileSize: 1000,
            Width: 640,
            Height: 480,
            Sha256: "abc",
            CapturedAt: DateTimeOffset.UtcNow,
            SentAt: DateTimeOffset.UtcNow,
            Purpose: TransferPurpose.Question,
            LocalFilePath: localPath,
            ThumbnailPath: null,
            ReceivedAt: DateTimeOffset.Parse("2026-01-02T03:04:05Z"),
            Status: TransferStatus.Completed,
            ErrorCode: null);

    [Fact]
    public void RecentItem_From_ExposesDisplayFieldsOnly()
    {
        var item = RecentItem.From(CompletedRecord(), "MEIZU 21");

        Assert.Equal(DateTimeOffset.Parse("2026-01-02T03:04:05Z").ToLocalTime().ToString("HH:mm:ss"), item.TimeLabel);
        Assert.Equal("photo.jpg", item.FileName);
        Assert.Equal("MEIZU 21", item.DeviceName);
        Assert.Equal(@"C:\inbox\photo.jpg", item.LocalFilePath);
        Assert.Equal("已接收", item.StatusLabel);
    }

    [Fact]
    public void RecentItem_DoesNotExposeTransferId()
    {
        var transferIdProperty = typeof(RecentItem).GetProperty("TransferId");
        Assert.Null(transferIdProperty);
    }

    [Fact]
    public void RecentItem_From_FailedRecord_ShowsErrorLabel()
    {
        var record = CompletedRecord() with { Status = TransferStatus.Failed, ErrorCode = "TIMEOUT" };
        var item = RecentItem.From(record, "MEIZU 21");

        Assert.Equal("TIMEOUT", item.StatusLabel);
    }

    [Fact]
    public void DeviceItem_From_TrustedDevice_UsesTrustedLabels()
    {
        var item = DeviceItem.From(new PairedDevice(
            "mobile-x", "MEIZU 21", "android", "tok-ref", "AA:BB",
            DateTimeOffset.UtcNow, null, IsTrusted: true));

        Assert.Equal("已信任", item.StatusLabel);
        Assert.Equal("#4CAF50", item.StatusColor);
        Assert.True(item.IsTrusted);
    }

    [Fact]
    public void DeviceItem_From_RevokedDevice_UsesRevokedLabels()
    {
        var item = DeviceItem.From(new PairedDevice(
            "mobile-x", "MEIZU 21", "android", "tok-ref", "AA:BB",
            DateTimeOffset.UtcNow, null, IsTrusted: false));

        Assert.Equal("已撤销", item.StatusLabel);
        Assert.Equal("#C62828", item.StatusColor);
        Assert.False(item.IsTrusted);
    }

    [Fact]
    public void SelectCurrent_PrefersTrustedDevice_OverRevokedButNewer()
    {
        var trustedOld = Device("mobile-old", true, ticks: 100);
        var revokedNew = Device("mobile-new", false, ticks: 500);

        var current = DeviceItem.SelectCurrent([revokedNew, trustedOld]);

        Assert.Equal("mobile-old", current!.DeviceId);
    }

    [Fact]
    public void SelectCurrent_AllRevoked_TakesMostRecent()
    {
        var older = Device("mobile-a", false, ticks: 100);
        var newer = Device("mobile-b", false, ticks: 900);

        var current = DeviceItem.SelectCurrent([older, newer]);

        Assert.Equal("mobile-b", current!.DeviceId);
    }

    [Fact]
    public void SelectCurrent_Empty_ReturnsNull()
    {
        Assert.Null(DeviceItem.SelectCurrent([]));
    }

    private static DeviceItem Device(string id, bool trusted, long ticks)
        => new(id, id, "android", "01-01 00:00", ticks, trusted ? "已信任" : "已撤销", trusted, trusted ? "#4CAF50" : "#C62828");
}