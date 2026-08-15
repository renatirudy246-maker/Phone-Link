namespace PhoneLink.Transport.Hosting;

public interface IReceiverHost : IAsyncDisposable
{
    Task StartAsync(CancellationToken cancellationToken);

    Task StopAsync(CancellationToken cancellationToken);
}