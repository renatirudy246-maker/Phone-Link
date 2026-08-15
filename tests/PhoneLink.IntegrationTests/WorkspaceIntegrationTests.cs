using PhoneLink.Core;

namespace PhoneLink.IntegrationTests;

public class WorkspaceIntegrationTests
{
    [Fact]
    public void Core_Desktop_Transport_Infrastructure_CompileTogether()
    {
        Assert.True(PhoneLink.Core.AppInfo.MaxImageSizeBytes > 0);
    }
}