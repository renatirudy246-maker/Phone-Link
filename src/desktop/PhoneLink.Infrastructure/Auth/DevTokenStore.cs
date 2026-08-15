using System.Security.Cryptography;
using System.Text;
using PhoneLink.Core.Auth;
using PhoneLink.Infrastructure.Paths;

namespace PhoneLink.Infrastructure.Auth;

/// <summary>
/// Phase 1 开发期测试 Token：首次运行生成 256-bit 随机值，保存到 data/dev-token.txt。
/// 仅用于 Phase 1 的本地联调，Phase 2 由每设备 Device Token 机制替代并移除本类。
/// </summary>
public sealed class DevTokenStore
{
    public string Token { get; }

    public DevTokenStore(AppPaths paths)
    {
        if (File.Exists(paths.DevTokenPath))
        {
            var existing = File.ReadAllText(paths.DevTokenPath).Trim();
            if (!string.IsNullOrEmpty(existing))
            {
                Token = existing;
                return;
            }
        }

        Token = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32));
        File.WriteAllText(paths.DevTokenPath, Token, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
    }
}

public sealed class DevTokenValidator : ITokenValidator
{
    private readonly DevTokenStore _store;

    public DevTokenValidator(DevTokenStore store)
    {
        _store = store;
    }

    public Task<TokenValidationResult> ValidateAsync(string? bearerToken, CancellationToken cancellationToken)
    {
        if (string.IsNullOrEmpty(bearerToken))
        {
            return Task.FromResult(new TokenValidationResult(IsValid: false));
        }

        var expected = Encoding.UTF8.GetBytes(_store.Token);
        var actual = Encoding.UTF8.GetBytes(bearerToken);
        var isValid = expected.Length == actual.Length
            && CryptographicOperations.FixedTimeEquals(expected, actual);

        return Task.FromResult(new TokenValidationResult(isValid));
    }
}