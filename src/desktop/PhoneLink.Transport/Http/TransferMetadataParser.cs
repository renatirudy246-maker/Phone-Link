using System.Text.Json;
using PhoneLink.Core.Errors;
using PhoneLink.Core.Models;
using PhoneLink.Core.Transfers;

namespace PhoneLink.Transport.Http;

/// <summary>
/// 解析上传 metadata JSON 为 TransferManifest。所有字段做长度/格式校验。
/// </summary>
public static class TransferMetadataParser
{
    public const int MaxMetadataBytes = 16 * 1024;

    public static TransferManifest Parse(string json)
    {
        using var document = JsonDocument.Parse(json);
        var root = document.RootElement;
        if (root.ValueKind != JsonValueKind.Object)
        {
            throw Invalid();
        }

        var transferId = ReadRequiredString(root, "transferId", 64, "alphanumeric-dash-underscore");
        if (!IsSafeId(transferId))
        {
            throw Invalid("transferId contains invalid characters.");
        }

        var senderDeviceId = ReadRequiredString(root, "senderDeviceId", 128, "alphanumeric-dash-underscore");
        if (!IsSafeId(senderDeviceId))
        {
            throw Invalid("senderDeviceId contains invalid characters.");
        }

        var originalFileName = ReadRequiredString(root, "originalFileName", 255, "file name");
        if (string.IsNullOrWhiteSpace(originalFileName))
        {
            throw Invalid("originalFileName is required.");
        }

        // 防御：展示用文件名只保留纯文件名（服务端落盘路径完全由服务端生成）。
        originalFileName = Path.GetFileName(originalFileName.Trim());
        if (string.IsNullOrEmpty(originalFileName))
        {
            throw Invalid("originalFileName is invalid.");
        }

        var mimeType = ReadRequiredString(root, "mimeType", 64, "mime type");
        var sha256 = ReadRequiredString(root, "sha256", 64, "sha256 hex");
        if (sha256.Length != 64 || !sha256.All(Uri.IsHexDigit))
        {
            throw Invalid("sha256 must be 64 hex characters.");
        }

        var purpose = TransferPurpose.Question;
        if (root.TryGetProperty("purpose", out var purposeElement))
        {
            if (purposeElement.ValueKind == JsonValueKind.String
                && !Enum.TryParse<TransferPurpose>(purposeElement.GetString(), ignoreCase: true, out purpose))
            {
                throw Invalid($"purpose must be one of: {string.Join(", ", Enum.GetNames<TransferPurpose>())}.");
            }
            else if (purposeElement.ValueKind == JsonValueKind.Number
                     && !Enum.IsDefined(typeof(TransferPurpose), purposeElement.GetInt32()))
            {
                throw Invalid("purpose out of range.");
            }
        }

        long? fileSize = ReadNullableInt64(root, "fileSize");
        if (fileSize is < 0)
        {
            throw Invalid("fileSize cannot be negative.");
        }

        int? width = ReadNullableInt32(root, "width");
        int? height = ReadNullableInt32(root, "height");
        if (width is <= 0 || height is <= 0)
        {
            throw Invalid("width/height must be positive when present.");
        }

        var capturedAt = ReadOffset(root, "capturedAt") ?? DateTimeOffset.UtcNow;
        var sentAt = ReadOffset(root, "sentAt") ?? DateTimeOffset.UtcNow;

        return new TransferManifest(
            TransferId: transferId,
            SenderDeviceId: senderDeviceId,
            OriginalFileName: originalFileName,
            MimeType: mimeType,
            FileSize: fileSize ?? 0,
            Width: width,
            Height: height,
            Sha256: sha256.ToLowerInvariant(),
            CapturedAt: capturedAt,
            SentAt: sentAt,
            Purpose: purpose);
    }

    private static string ReadRequiredString(
        JsonElement root,
        string property,
        int maxLength,
        string description)
    {
        if (!root.TryGetProperty(property, out var element) || element.ValueKind != JsonValueKind.String)
        {
            throw Invalid($"{property} ({description}) is required.");
        }

        var value = element.GetString()?.Trim();
        if (string.IsNullOrEmpty(value) || value.Length > maxLength)
        {
            throw Invalid($"{property} must be 1..{maxLength} characters.");
        }

        return value;
    }

    private static long? ReadNullableInt64(JsonElement root, string property)
    {
        if (root.TryGetProperty(property, out var element) && element.ValueKind == JsonValueKind.Number)
        {
            return element.GetInt64();
        }

        return null;
    }

    private static int? ReadNullableInt32(JsonElement root, string property)
    {
        if (root.TryGetProperty(property, out var element) && element.ValueKind == JsonValueKind.Number)
        {
            return element.GetInt32();
        }

        return null;
    }

    private static DateTimeOffset? ReadOffset(JsonElement root, string property)
    {
        if (root.TryGetProperty(property, out var element) && element.ValueKind == JsonValueKind.String)
        {
            return DateTimeOffset.TryParse(element.GetString(), out var value) ? value : null;
        }

        return null;
    }

    private static bool IsSafeId(string value)
        => value.All(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_');

    private static TransferProcessingException Invalid(string message = "Malformed metadata.")
        => new(new ApiError(ErrorCodes.InvalidRequest, message, Retryable: false));
}