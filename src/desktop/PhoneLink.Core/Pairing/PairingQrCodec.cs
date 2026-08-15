using System.Text;
using System.Text.Json;

namespace PhoneLink.Core.Pairing;

/// <summary>
/// QR 配对 payload：compact JSON + Base64URL（无 padding）。
/// 逻辑字段与 AGENT_BUILD_SPEC §8.1 一致，JSON 字段名固定为协议契约。
/// </summary>
public sealed record PairingQrPayload(
    int ProtocolVersion,
    string DesktopDeviceId,
    string DesktopDeviceName,
    string Host,
    int Port,
    string OneTimeToken,
    string CertificateFingerprint,
    DateTimeOffset ExpiresAt);

public static class PairingQrCodec
{
    public const int MaxPayloadLength = 2048;

    public static string Encode(PairingQrPayload payload)
    {
        var json = JsonSerializer.Serialize(payload);
        return Base64UrlEncode(Encoding.UTF8.GetBytes(json));
    }

    /// <summary>畸形 payload 抛 FormatException，调用方不得 crash。</summary>
    public static PairingQrPayload Decode(string payload)
    {
        if (string.IsNullOrWhiteSpace(payload) || payload.Length > MaxPayloadLength)
        {
            throw new FormatException("QR payload is missing or too large.");
        }

        byte[] jsonBytes;
        try
        {
            jsonBytes = Base64UrlDecode(payload);
        }
        catch (FormatException)
        {
            throw new FormatException("QR payload is not valid Base64URL.");
        }

        PairingQrPayload result;
        try
        {
            result = JsonSerializer.Deserialize<PairingQrPayload>(jsonBytes)
                ?? throw new JsonException("QR payload is empty.");
        }
        catch (JsonException ex)
        {
            throw new FormatException("QR payload is not valid compact JSON.", ex);
        }

        Validate(result);
        return result;
    }

    private static void Validate(PairingQrPayload payload)
    {
        if (string.IsNullOrWhiteSpace(payload.DesktopDeviceId)
            || string.IsNullOrWhiteSpace(payload.DesktopDeviceName)
            || string.IsNullOrWhiteSpace(payload.Host)
            || string.IsNullOrWhiteSpace(payload.OneTimeToken)
            || string.IsNullOrWhiteSpace(payload.CertificateFingerprint))
        {
            throw new FormatException("QR payload has missing required fields.");
        }

        if (payload.Port is < 1 or > 65535)
        {
            throw new FormatException("QR payload has invalid port.");
        }
    }

    private static string Base64UrlEncode(byte[] bytes)
        => Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static byte[] Base64UrlDecode(string input)
    {
        var normalized = input.Replace('-', '+').Replace('_', '/');
        normalized = normalized.PadRight(normalized.Length + (4 - normalized.Length % 4) % 4, '=');
        return Convert.FromBase64String(normalized);
    }
}