using PhoneLink.Core.Models;

namespace PhoneLink.Core.Pairing;

public interface IPairedDeviceRepository
{
    Task<PairedDevice?> GetByDeviceIdAsync(string deviceId, CancellationToken cancellationToken);

    Task<List<PairedDevice>> ListAllAsync(CancellationToken cancellationToken);

    /// <summary>已存在设备则更新（重新配对时轮换 token），否则新增。</summary>
    Task UpsertAsync(PairedDevice device, CancellationToken cancellationToken);

    Task SetLastSeenAsync(
        string deviceId,
        DateTimeOffset lastSeenAt,
        string? lastKnownEndpoint,
        CancellationToken cancellationToken);

    Task SetRevokedAsync(string deviceId, bool revoked, CancellationToken cancellationToken);
}