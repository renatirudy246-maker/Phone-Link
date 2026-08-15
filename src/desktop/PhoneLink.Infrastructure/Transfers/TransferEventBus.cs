using PhoneLink.Core.Models;
using PhoneLink.Core.Transfers;

namespace PhoneLink.Infrastructure.Transfers;

public sealed class TransferEventBus : ITransferEventSource, ITransferEventPublisher
{
    private readonly object _gate = new();

    public event EventHandler<TransferRecord>? Received;

    public void Publish(TransferRecord record)
    {
        EventHandler<TransferRecord>? handlers;
        lock (_gate)
        {
            handlers = Received;
        }

        handlers?.Invoke(this, record);
    }
}