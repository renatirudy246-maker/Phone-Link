using PhoneLink.Core.Models;

namespace PhoneLink.Core.Pairing;

public sealed record CreatePairingSessionResult(
    PairingSession Session,
    string QrPayload);

public sealed record ConsumePairingSessionResult(
    PairingSession? Session,
    string? ErrorCode);

/// <summary>
/// PairingSession 生命周期：创建（QR payload）、一次性消费（/v1/pair）、过期清理。
/// </summary>
public interface IPairingSessionService
{
    Task<CreatePairingSessionResult> CreateAsync(CancellationToken cancellationToken);

    /// <summary>成功返回 Session（已标记 consumed）；失败返回 ErrorCode（PAIR_TOKEN_INVALID/EXPIRED/ALREADY_USED）。</summary>
    Task<ConsumePairingSessionResult> ConsumeAsync(string oneTimeToken, CancellationToken cancellationToken);

    Task<int> CleanupExpiredAsync(CancellationToken cancellationToken);
}