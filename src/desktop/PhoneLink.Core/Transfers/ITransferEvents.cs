using PhoneLink.Core.Models;

namespace PhoneLink.Core.Transfers;

public interface ITransferEventSource
{
    event EventHandler<TransferRecord>? Received;
}

public interface ITransferEventPublisher
{
    void Publish(TransferRecord record);
}