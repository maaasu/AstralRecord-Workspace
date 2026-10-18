using System.Buffers.Binary;
using System.IO.Pipes;
using System.Text;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent;

public interface IRunnerClient
{
    Task<RunnerSnapshot> GetSnapshotAsync(CancellationToken cancellationToken);
    Task<RunnerSnapshot> ApplyInventoryAsync(InventoryEnvelope inventory, CancellationToken cancellationToken);
}

public sealed class RunnerClient(AgentConfiguration configuration) : IRunnerClient
{
    public Task<RunnerSnapshot> GetSnapshotAsync(CancellationToken cancellationToken) => SendAsync<RunnerSnapshot>("GetSnapshot", new { }, cancellationToken);
    public Task<RunnerSnapshot> ApplyInventoryAsync(InventoryEnvelope inventory, CancellationToken cancellationToken) => SendAsync<RunnerSnapshot>("ApplyInventory", new ApplyInventoryRequest(inventory), cancellationToken);
    private async Task<T> SendAsync<T>(string method, object body, CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(5));
        using var pipe = new NamedPipeClientStream(".", configuration.RunnerPipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
        await pipe.ConnectAsync(timeout.Token);
        var request = new IpcRequest(ControlProtocol.Version, Guid.NewGuid(), method, ControlJson.Element(body));
        var json = Encoding.UTF8.GetBytes(ControlJson.Serialize(request));
        if (json.Length > ControlProtocol.MaxMessageBytes) throw new AgentFault(ErrorCode.ProtocolMismatch);
        var header = new byte[4]; BinaryPrimitives.WriteUInt32LittleEndian(header, (uint)json.Length);
        await pipe.WriteAsync(header, timeout.Token);
        await pipe.WriteAsync(json, timeout.Token);
        await pipe.FlushAsync(timeout.Token);
        await pipe.ReadExactlyAsync(header, timeout.Token);
        var length = BinaryPrimitives.ReadUInt32LittleEndian(header);
        if (length is 0 or > ControlProtocol.MaxMessageBytes) throw new AgentFault(ErrorCode.ProtocolMismatch);
        var responseBytes = new byte[(int)length];
        await pipe.ReadExactlyAsync(responseBytes, timeout.Token);
        var response = ControlJson.Deserialize<IpcResponse>(new UTF8Encoding(false, true).GetString(responseBytes));
        if (response.RequestId != request.RequestId) throw new AgentFault(ErrorCode.ProtocolMismatch);
        if (!response.Ok) throw new AgentFault(Enum.TryParse<ErrorCode>(response.ErrorCode, out var code) ? code : ErrorCode.ProtocolMismatch);
        return ControlJson.Deserialize<T>(response.Body.GetRawText());
    }
}

public sealed class InventoryApplier(AgentConfiguration configuration, AgentStore store, IRunnerClient runner)
{
    public async Task ApplyAsync(InventoryEnvelope envelope, CancellationToken cancellationToken)
    {
        var inventory = InventoryValidation.ReadEnvelope(envelope, configuration.HostId);
        AgentResourceBoundary.ValidateInventory(inventory, configuration.LocalResources);
        store.PrepareInventory(envelope);
        var snapshot = await runner.ApplyInventoryAsync(envelope, cancellationToken);
        if (snapshot.AppliedInventoryRevision != envelope.Revision || snapshot.RunnerEpoch == Guid.Empty) throw new AgentFault(ErrorCode.RecoveryRequired);
        store.CommitInventory(envelope);
    }
}
