using System.Text.Json;
using Microsoft.AspNetCore.Http;
using PhoneLink.Core.Errors;
using PhoneLink.Core.Models;
using PhoneLink.Core.Transfers;
using PhoneLink.Transport.Http;

namespace PhoneLink.Transport.Tests;

public class BearerTokenParserTests
{
    [Theory]
    [InlineData(null, null)]
    [InlineData("", null)]
    [InlineData("Basic abc", null)]
    [InlineData("Bearer", null)]
    [InlineData("Bearer   ", null)]
    [InlineData("Bearer abc123", "abc123")]
    [InlineData("bearer abc123", "abc123")]
    [InlineData("Bearer abc", "abc")]
    public void Extract_ReturnsExpected(string? header, string? expected)
    {
        Assert.Equal(expected, BearerTokenParser.Extract(header));
    }
}

public class ApiErrorMapperTests
{
    [Theory]
    [InlineData(ErrorCodes.AuthInvalid, StatusCodes.Status401Unauthorized)]
    [InlineData(ErrorCodes.DeviceRevoked, StatusCodes.Status403Forbidden)]
    [InlineData(ErrorCodes.FileTooLarge, StatusCodes.Status413PayloadTooLarge)]
    [InlineData(ErrorCodes.UnsupportedMediaType, StatusCodes.Status415UnsupportedMediaType)]
    [InlineData(ErrorCodes.TransferHashMismatch, StatusCodes.Status422UnprocessableEntity)]
    [InlineData(ErrorCodes.DiskWriteFailed, StatusCodes.Status500InternalServerError)]
    [InlineData(ErrorCodes.NotFound, StatusCodes.Status404NotFound)]
    [InlineData(ErrorCodes.InvalidRequest, StatusCodes.Status400BadRequest)]
    public void StatusCodeFor_MapsKnownCodes(string code, int expectedStatus)
    {
        Assert.Equal(expectedStatus, ApiErrorMapper.StatusCodeFor(code));
    }
}

public class TransferMetadataParserTests
{
    private static string ValidMetadata(string transferId = "t-123", string? fileName = "question.jpg")
        => $$"""
        {
          "transferId": "{{transferId}}",
          "senderDeviceId": "mobile-1",
          "originalFileName": "{{fileName}}",
          "mimeType": "image/jpeg",
          "fileSize": 1024,
          "width": 3000,
          "height": 4000,
          "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "capturedAt": "2026-08-15T10:00:00+08:00",
          "sentAt": "2026-08-15T10:00:01+08:00",
          "purpose": "Question"
        }
        """;

    [Fact]
    public void Parse_ValidMetadata_ReturnsManifest()
    {
        var manifest = TransferMetadataParser.Parse(ValidMetadata());

        Assert.Equal("t-123", manifest.TransferId);
        Assert.Equal("mobile-1", manifest.SenderDeviceId);
        Assert.Equal("question.jpg", manifest.OriginalFileName);
        Assert.Equal("image/jpeg", manifest.MimeType);
        Assert.Equal(1024, manifest.FileSize);
        Assert.Equal(3000, manifest.Width);
        Assert.Equal(4000, manifest.Height);
        Assert.Equal(TransferPurpose.Question, manifest.Purpose);
    }

    [Fact]
    public void Parse_MissingTransferId_ThrowsInvalidRequest()
    {
        var json = ValidMetadata().Replace("\"transferId\": \"t-123\",", string.Empty);
        var ex = Assert.Throws<TransferProcessingException>(() => TransferMetadataParser.Parse(json));
        Assert.Equal(ErrorCodes.InvalidRequest, ex.Error.Code);
    }

    [Fact]
    public void Parse_TransferIdWithSlash_Throws()
    {
        Assert.Throws<TransferProcessingException>(
            () => TransferMetadataParser.Parse(ValidMetadata(transferId: "a/b")));
    }

    [Fact]
    public void Parse_InvalidSha256_Throws()
    {
        var json = ValidMetadata().Replace(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            new string('Z', 64));
        Assert.Throws<TransferProcessingException>(() => TransferMetadataParser.Parse(json));
    }

    [Fact]
    public void Parse_InvalidPurpose_Throws()
    {
        var json = ValidMetadata().Replace("\"purpose\": \"Question\"", "\"purpose\": \"Banana\"");
        Assert.Throws<TransferProcessingException>(() => TransferMetadataParser.Parse(json));
    }

    [Fact]
    public void Parse_NegativeWidth_Throws()
    {
        var json = ValidMetadata().Replace("\"width\": 3000", "\"width\": -1");
        Assert.Throws<TransferProcessingException>(() => TransferMetadataParser.Parse(json));
    }

    [Fact]
    public void Parse_PathTraversalFileName_StrippedToFileName()
    {
        var manifest = TransferMetadataParser.Parse(ValidMetadata(fileName: "../../../evil.jpg"));
        Assert.Equal("evil.jpg", manifest.OriginalFileName);
    }

    [Fact]
    public void Parse_WindowsPathFileName_StrippedToFileName()
    {
        var manifest = TransferMetadataParser.Parse(
            ValidMetadata(fileName: "C:\\\\Users\\\\me\\\\photo.png"));
        Assert.Equal("photo.png", manifest.OriginalFileName);
    }

    [Fact]
    public void Parse_MissingTimestamps_DefaultsToNow()
    {
        var json = ValidMetadata()
            .Replace("          \"capturedAt\": \"2026-08-15T10:00:00+08:00\",\n", string.Empty)
            .Replace("          \"sentAt\": \"2026-08-15T10:00:01+08:00\",\n", string.Empty);
        var manifest = TransferMetadataParser.Parse(json);
        Assert.True(manifest.CapturedAt <= DateTimeOffset.UtcNow.AddSeconds(5));
        Assert.True(manifest.SentAt <= DateTimeOffset.UtcNow.AddSeconds(5));
    }

    [Fact]
    public void Parse_InvalidJson_Throws()
    {
        Assert.ThrowsAny<JsonException>(() => TransferMetadataParser.Parse("not json"));
    }
}