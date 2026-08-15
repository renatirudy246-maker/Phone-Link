using PhoneLink.Core;

namespace PhoneLink.Transport.Tests;

public class TransportScaffoldTests
{
    [Fact]
    public void TransportLayer_TargetsProtocolVersionOne()
    {
        Assert.Equal(1, AppInfo.ProtocolVersion);
    }

    [Fact]
    public void ServiceType_MatchesDesign()
    {
        Assert.Equal("_phonelink._tcp.local", AppInfo.ServiceType);
    }
}