using System.Buffers.Binary;
using System.Text;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Runner;

public static class PipeProtocol
{
    public static async Task<string> ReadAsync(Stream stream, CancellationToken cancellationToken)
    {
        var header = new byte[4];
        await stream.ReadExactlyAsync(header, cancellationToken);
        var length = BinaryPrimitives.ReadUInt32LittleEndian(header);
        if (length is 0 or > ControlProtocol.MaxMessageBytes) throw new RunnerFault(ErrorCode.ProtocolMismatch);
        var body = new byte[(int)length];
        await stream.ReadExactlyAsync(body, cancellationToken);
        return new UTF8Encoding(false, true).GetString(body);
    }
    public static async Task WriteAsync<T>(Stream stream, T message, CancellationToken cancellationToken)
    {
        var body = Encoding.UTF8.GetBytes(ControlJson.Serialize(message));
        if (body.Length is 0 or > ControlProtocol.MaxMessageBytes) throw new RunnerFault(ErrorCode.ProtocolMismatch);
        var header = new byte[4]; BinaryPrimitives.WriteUInt32LittleEndian(header, (uint)body.Length);
        await stream.WriteAsync(header, cancellationToken);
        await stream.WriteAsync(body, cancellationToken);
        await stream.FlushAsync(cancellationToken);
    }
}

public sealed class RunnerDispatcher(RunnerStore store)
{
    private sealed record SnapshotBody(Guid[]? ServerIds = null);
    public IpcResponse Dispatch(IpcRequest request, bool isLocalAdministrator = false)
    {
        try
        {
            if (request.ProtocolVersion != ControlProtocol.Version || request.RequestId == Guid.Empty) throw new RunnerFault(ErrorCode.ProtocolMismatch);
            var body = request.Method switch
            {
                "GetSnapshot" => Snapshot(request),
                "ApplyInventory" => ControlJson.Element(store.ApplyInventory(ControlJson.Deserialize<ApplyInventoryRequest>(request.Body.GetRawText()).Inventory)),
                "AcquireHold" => ControlJson.Element(store.AcquireHold(ControlJson.Deserialize<AcquireHoldRequest>(request.Body.GetRawText()))),
                "ReleaseHold" => Release(request),
                "Execute" => store.ProcessControlEnabled ? ControlJson.Element(store.Execute(ControlJson.Deserialize<ExecuteRequest>(request.Body.GetRawText()))) : throw new RunnerFault(ErrorCode.UnsupportedCapability),
                "GetExecution" => ControlJson.Element(store.GetExecution(ControlJson.Deserialize<GetExecutionRequest>(request.Body.GetRawText()).OperationId)),
                "ReadLogs" => ControlJson.Element(store.ReadLogs(ControlJson.Deserialize<ReadLogsRequest>(request.Body.GetRawText()))),
                "InspectRecovery" => ControlJson.Element(store.InspectRecovery(ControlJson.Deserialize<InspectRecoveryRequest>(request.Body.GetRawText()))),
                "Recover" => ControlJson.Element(store.Recover(ControlJson.Deserialize<RunnerRecoveryRequest>(request.Body.GetRawText()), isLocalAdministrator)),
                _ => throw new RunnerFault(ErrorCode.UnsupportedCapability)
            };
            return new IpcResponse(request.RequestId, true, body);
        }
        catch (RunnerFault fault) { return Failure(request.RequestId, fault.Code); }
        catch (Microsoft.Data.Sqlite.SqliteException) { return Failure(request.RequestId, ErrorCode.RecoveryRequired); }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or System.ComponentModel.Win32Exception) { return Failure(request.RequestId, ErrorCode.RecoveryRequired); }
        catch (Exception exception) when (exception is ArgumentException or System.Text.Json.JsonException or NullReferenceException or InvalidOperationException or OverflowException)
        { return Failure(request.RequestId, ErrorCode.ProtocolMismatch); }
    }
    private System.Text.Json.JsonElement Snapshot(IpcRequest request)
    {
        var query = ControlJson.Deserialize<SnapshotBody>(request.Body.GetRawText());
        var snapshot = store.GetSnapshot();
        if (query.ServerIds is not null)
        {
            if (query.ServerIds.Length > 32 || query.ServerIds.Any(id => id == Guid.Empty)) throw new RunnerFault(ErrorCode.ProtocolMismatch);
            snapshot = snapshot with { Servers = snapshot.Servers.Where(s => query.ServerIds.Contains(s.ServerId)).ToArray() };
        }
        return ControlJson.Element(snapshot);
    }
    private System.Text.Json.JsonElement Release(IpcRequest request)
    {
        store.ReleaseHold(ControlJson.Deserialize<ReleaseHoldRequest>(request.Body.GetRawText()));
        return ControlJson.Element(new { });
    }
    public static IpcResponse Failure(Guid requestId, ErrorCode code) => new(requestId, false, ControlJson.Element(new { }), code.ToString());
}
