using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Hubs;
using AstralRecordControl.Web.Inventory;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Operations;

public interface IAgentOperationTransport
{
    Task<PreparedHold> PrepareAsync(Guid hostId, AcquireHoldRequest request, CancellationToken cancellationToken);
    Task ReleaseAsync(Guid hostId, ReleaseHoldRequest request, CancellationToken cancellationToken);
    Task<ExecutionSnapshot> DispatchAsync(Guid hostId, Guid operationId, string commandJson, string hash, CancellationToken cancellationToken);
    Task<ExecutionSnapshot> QueryAsync(Guid hostId, Guid operationId, CancellationToken cancellationToken);
    Task<RunnerSnapshot> SnapshotAsync(Guid hostId, CancellationToken cancellationToken);
    Task<LogBatch> ReadLogsAsync(Guid hostId, ReadLogsRequest request, CancellationToken cancellationToken);
    Task<RecoveryInspection> InspectRecoveryAsync(Guid hostId, Guid serverId, CancellationToken cancellationToken);
}

public sealed class AgentOperationTransport(IHubContext<AgentHub> hub, AgentConnectionRegistry connections, ControlDbContext db, TimeProvider clock) : IAgentOperationTransport
{
    private async Task<AgentConnection> RequireAsync(Guid hostId)
    {
        var active = connections.Current(hostId) ?? throw new InventoryFault(503, "HostOffline");
        var host = await db.Hosts.AsNoTracking().SingleOrDefaultAsync(x => x.HostId == hostId) ?? throw new InventoryFault(404, "NotFound");
        if (!host.Enabled || host.RecoveryRequired || host.ConnectionState != HostConnectionState.Online || host.ConnectionEpoch != active.Epoch || host.LastSeenAtUtc == null || clock.GetUtcNow() - host.LastSeenAtUtc > TimeSpan.FromSeconds(30)) throw new InventoryFault(503, "HostOffline");
        if (!await db.AgentCredentials.AsNoTracking().AnyAsync(x => x.HostId == hostId && x.CredentialId == active.CredentialId && x.RevokedAtUtc == null)) throw new InventoryFault(401, "AgentDisabled");
        return active;
    }
    private static CancellationTokenSource Deadline(CancellationToken token)
    {
        var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
        timeout.CancelAfter(TimeSpan.FromSeconds(5));
        return timeout;
    }
    public async Task<PreparedHold> PrepareAsync(Guid hostId, AcquireHoldRequest request, CancellationToken cancellationToken)
    {
        var current = await RequireAsync(hostId);
        using var timeout = Deadline(cancellationToken);
        return await hub.Clients.Client(current.ConnectionId).InvokeAsync<PreparedHold>("PrepareHold", new PrepareHoldRequest(current.Epoch, request), timeout.Token);
    }
    public async Task ReleaseAsync(Guid hostId, ReleaseHoldRequest request, CancellationToken cancellationToken)
    {
        var current = await RequireAsync(hostId);
        using var timeout = Deadline(cancellationToken);
        var response = await hub.Clients.Client(current.ConnectionId).InvokeAsync<ReleasePreparedHoldResponse>("ReleaseHold", new ReleasePreparedHoldRequest(current.Epoch, request), timeout.Token);
        if (response.ReservationId != request.ReservationId || !response.Released) throw new InventoryFault(409, "RecoveryRequired");
    }
    public async Task<ExecutionSnapshot> DispatchAsync(Guid hostId, Guid operationId, string commandJson, string hash, CancellationToken cancellationToken)
    {
        var current = await RequireAsync(hostId);
        using var timeout = Deadline(cancellationToken);
        return await hub.Clients.Client(current.ConnectionId).InvokeAsync<ExecutionSnapshot>("DispatchOperation", new DispatchOperationRequest(1, current.Epoch, operationId, commandJson, hash), timeout.Token);
    }
    public async Task<ExecutionSnapshot> QueryAsync(Guid hostId, Guid operationId, CancellationToken cancellationToken)
    {
        var current = await RequireAsync(hostId);
        using var timeout = Deadline(cancellationToken);
        return await hub.Clients.Client(current.ConnectionId).InvokeAsync<ExecutionSnapshot>("GetExecution", new AgentExecutionRequest(current.Epoch, operationId), timeout.Token);
    }
    public async Task<RunnerSnapshot> SnapshotAsync(Guid hostId, CancellationToken cancellationToken)
    {
        var current = await RequireAsync(hostId);
        using var timeout = Deadline(cancellationToken);
        return await hub.Clients.Client(current.ConnectionId).InvokeAsync<RunnerSnapshot>("GetSnapshot", new AgentSnapshotRequest(current.Epoch), timeout.Token);
    }
    public async Task<LogBatch> ReadLogsAsync(Guid hostId, ReadLogsRequest request, CancellationToken cancellationToken)
    {
        var current = await RequireAsync(hostId);
        using var timeout = Deadline(cancellationToken);
        return await hub.Clients.Client(current.ConnectionId).InvokeAsync<LogBatch>("ReadLogs", new AgentReadLogsRequest(current.Epoch, request), timeout.Token);
    }
    public async Task<RecoveryInspection> InspectRecoveryAsync(Guid hostId, Guid serverId, CancellationToken cancellationToken)
    {
        var current = await RequireAsync(hostId);
        using var timeout = Deadline(cancellationToken);
        return await hub.Clients.Client(current.ConnectionId).InvokeAsync<RecoveryInspection>("InspectRecovery", new AgentInspectRecoveryRequest(current.Epoch, serverId), timeout.Token);
    }
}
