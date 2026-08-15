using System.Security.Cryptography;

namespace PhoneLink.Core.Auth;

/// <summary>
/// 安全随机 Token 生成：256-bit（32 字节）Base64URL。
/// 用于一次性 Pair Token 与长期 Device Token。
/// </summary>
public static class TokenGenerator
{
    public static string GenerateSecureToken()
    {
        var bytes = RandomNumberGenerator.GetBytes(32);
        return Convert.ToBase64String(bytes)
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');
    }
}