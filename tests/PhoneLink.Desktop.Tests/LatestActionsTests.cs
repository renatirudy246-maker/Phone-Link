using System.IO;
using PhoneLink.Desktop.Commands;

namespace PhoneLink.Desktop.Tests;

public sealed class LatestActionsTests
{
    [Fact]
    public void IsValidImagePath_EmptyOrNull_ReturnsFalse()
    {
        Assert.False(LatestActions.IsValidImagePath(null));
        Assert.False(LatestActions.IsValidImagePath(string.Empty));
        Assert.False(LatestActions.IsValidImagePath("   "));
    }

    [Fact]
    public void IsValidImagePath_MissingFile_ReturnsFalse()
    {
        Assert.False(LatestActions.IsValidImagePath(@"C:\nonexistent\missing.jpg"));
    }

    [Fact]
    public void IsValidImagePath_ExistingFile_ReturnsTrue()
    {
        var temp = Path.GetTempFileName();
        try
        {
            Assert.True(LatestActions.IsValidImagePath(temp));
        }
        finally
        {
            File.Delete(temp);
        }
    }

    [Theory]
    [InlineData(@"C:\inbox\a.jpg", @"/select, ""C:\inbox\a.jpg""")]
    [InlineData(@"C:\inbox dir\my photo.jpg", @"/select, ""C:\inbox dir\my photo.jpg""")]
    [InlineData(@"C:\x\,comma.jpg", @"/select, ""C:\x\,comma.jpg""")]
    public void BuildExplorerSelectArgument_QuotesPath(string path, string expected)
    {
        Assert.Equal(expected, LatestActions.BuildExplorerSelectArgument(path));
    }

    [Fact]
    public void CopyImage_MissingFile_ReturnsFalse()
    {
        Assert.False(LatestActions.CopyImage(@"C:\nonexistent\missing.jpg"));
    }
}