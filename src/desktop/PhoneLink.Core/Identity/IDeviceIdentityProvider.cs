using PhoneLink.Core.Models;

namespace PhoneLink.Core.Identity;

public interface IDeviceIdentityProvider
{
    Task<DeviceIdentity> GetIdentityAsync(CancellationToken cancellationToken);
}