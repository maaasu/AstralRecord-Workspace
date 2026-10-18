using System.Text.Json;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Inventory;

public sealed class AgentProtocolService(ControlDbContext db, InventoryStore store, AgentCredentialService credentials, AgentConnectionRegistry connections, TimeProvider clock)
{
    public async Task<HelloAck> HelloAsync(AgentIdentity identity, AgentHello hello, string connectionId, Action abort)
    {
        var result = await store.WriteAsync(async () =>
        {
            await credentials.EnsureActiveAsync(identity);
            var host = await store.HostAsync(identity.HostId);
            string? fault = null;
            if (hello.ProtocolVersions == null || !hello.ProtocolVersions.Contains(1)) { host.ConnectionState = HostConnectionState.Incompatible; fault = "ProtocolMismatch"; }
            else if (hello.AgentStoreId == Guid.Empty || hello.RunnerEpoch == Guid.Empty || hello.MaxLocalEventSeq < 0 || hello.AppliedInventoryRevision < 0 || string.IsNullOrWhiteSpace(hello.AgentVersion) || hello.AgentVersion.Length > 128) throw new InventoryFault(400, "InvalidInput");
            else if (host.RecoveryRequired || host.AgentStoreId.HasValue && host.AgentStoreId != hello.AgentStoreId || hello.MaxLocalEventSeq < host.AckAgentEventSeq || hello.AppliedInventoryRevision < host.AppliedInventoryRevision || hello.AppliedInventoryRevision > host.DesiredInventoryRevision)
            { host.RecoveryRequired = true; host.ConnectionState = HostConnectionState.Offline; fault = "RecoveryRequired"; }
            if (fault != null)
            {
                host.ConnectionEpoch = null; host.InventoryErrorCode = fault; connections.RevokeHost(host.HostId);
                return (Ack: (HelloAck?)null, Fault: fault);
            }
            ValidateResources(hello.LocalResources);
            if (hello.Capabilities == null || hello.Capabilities.Length > 64 || hello.Capabilities.Any(c => string.IsNullOrWhiteSpace(c) || c.Length > 128) || !hello.Capabilities.Contains("P0.Inventory")) throw new InventoryFault(409, "UnsupportedCapability");
            host.AgentStoreId ??= hello.AgentStoreId;
            if (host.RunnerEpoch != hello.RunnerEpoch) host.RuntimeSnapshotJson = "[]";
            host.ConnectionEpoch = Guid.NewGuid(); host.RunnerEpoch = hello.RunnerEpoch;
            host.ConnectionState = HostConnectionState.Reconciling; host.LastSeenAtUtc = clock.GetUtcNow();
            host.CapabilitiesJson = ControlJson.Serialize(hello.Capabilities); host.ResourcesJson = ControlJson.Serialize(hello.LocalResources);
            connections.Register(new(host.HostId, identity.CredentialId, host.ConnectionEpoch.Value, connectionId, abort));
            return (Ack: (HelloAck?)new HelloAck(1, host.ConnectionEpoch.Value, host.AckAgentEventSeq, host.DesiredInventoryRevision), Fault: (string?)null);
        });
        if (result.Fault != null) throw new InventoryFault(409, result.Fault);
        return result.Ack!;
    }

    public async Task<ReconcileAck> ReconcileAsync(AgentIdentity identity, ReconcileRequest request, string connectionId) => await store.WriteAsync(async () =>
    {
        var host = await RequireAsync(identity, request.ConnectionEpoch, connectionId);
        if (request.RunnerEpoch == Guid.Empty || request.RunnerEpoch != host.RunnerEpoch) throw new InventoryFault(409, "StateConflict");
        if (request.AppliedInventoryRevision < host.AppliedInventoryRevision || request.AppliedInventoryRevision > host.DesiredInventoryRevision) throw new InventoryFault(409, "RecoveryRequired");
        if (request.Operations == null || request.Holds == null || request.Operations.Length > 0 || request.Holds.Length > 0) throw new InventoryFault(409, "UnsupportedCapability");
        await ValidateRuntimeAsync(identity.HostId, request.Servers);
        // The handshake cannot assert that an unacknowledged desired revision applied.
        // The exact revision/hash is acknowledged through InventoryApplied.
        host.RuntimeSnapshotJson = ControlJson.Serialize(request.Servers);
        host.ConnectionState = HostConnectionState.Online; host.LastSeenAtUtc = clock.GetUtcNow();
        return new ReconcileAck(host.AckAgentEventSeq, [], request.AppliedInventoryRevision != host.DesiredInventoryRevision || host.AppliedInventoryRevision != request.AppliedInventoryRevision);
    });

    public async Task HeartbeatAsync(AgentIdentity identity, AgentHeartbeat request, string connectionId) => await store.WriteAsync(async () =>
    {
        var host = await RequireAsync(identity, request.ConnectionEpoch, connectionId);
        if (host.ConnectionState != HostConnectionState.Online) throw new InventoryFault(409, "StateConflict");
        if (request.RunnerEpoch != host.RunnerEpoch || request.RunnerEpoch == Guid.Empty) throw new InventoryFault(409, "StateConflict");
        if (request.ObservedAtUtc.Offset != TimeSpan.Zero || request.Metrics == null || request.Metrics.Length > 0) throw new InventoryFault(400, "InvalidInput");
        await ValidateRuntimeAsync(identity.HostId, request.ServerSnapshots);
        host.LastSeenAtUtc = clock.GetUtcNow(); host.RuntimeSnapshotJson = ControlJson.Serialize(request.ServerSnapshots);
        return true;
    });

    public async Task ApplyAckAsync(AgentIdentity identity, InventoryApplied request, string connectionId) => await store.WriteAsync(async () =>
    {
        var host = await RequireAsync(identity, request.ConnectionEpoch, connectionId);
        if (host.ConnectionState != HostConnectionState.Online) throw new InventoryFault(409, "StateConflict");
        var version = await db.InventoryVersions.SingleOrDefaultAsync(v => v.HostId == host.HostId && v.Revision == request.InventoryRevision) ?? throw new InventoryFault(409, "StaleRevision");
        if (!ControlProtocol.IsHash(request.Sha256) || version.Sha256 != request.Sha256) throw new InventoryFault(409, "HashMismatch");
        if (request.InventoryRevision < host.AppliedInventoryRevision) throw new InventoryFault(409, "StaleRevision");
        if (request.Success)
        {
            host.AppliedInventoryRevision = request.InventoryRevision; host.InventoryErrorCode = null;
            var snapshot = InventoryValidation.ReadEnvelope(new(1, host.HostId, version.Revision, version.SnapshotJson, version.Sha256), host.HostId);
            var rows = await db.Servers.Where(s => s.HostId == host.HostId).ToListAsync();
            foreach (var config in snapshot.Servers) rows.Single(s => s.ServerId == config.ServerId).AppliedRevision = config.Revision;
        }
        else
        {
            if (request.ErrorCode == null || !Enum.TryParse<ErrorCode>(request.ErrorCode, false, out var code) || !Enum.IsDefined(code)) throw new InventoryFault(400, "InvalidInput");
            host.InventoryErrorCode = request.ErrorCode;
        }
        host.LastSeenAtUtc = clock.GetUtcNow(); return true;
    });

    public async Task<EventAck> PublishEventsAsync(AgentIdentity identity, EventBatch request, string? connectionId = null) => await store.WriteAsync(async () =>
    {
        var host = await RequireAsync(identity, request.ConnectionEpoch, connectionId);
        if (host.ConnectionState != HostConnectionState.Online) throw new InventoryFault(409, "StateConflict");
        if (request.Events == null || request.Events.Length > 128) throw new InventoryFault(400, "InvalidInput");
        foreach (var item in request.Events)
        {
            if (item.EventSeq < 1 || item.OccurredAtUtc.Offset != TimeSpan.Zero || item.OperationId.HasValue || item.StatusVersion.HasValue || item.Body.ValueKind != JsonValueKind.Object || string.IsNullOrWhiteSpace(item.EventType) || item.EventType.Length > 128 || System.Text.Encoding.UTF8.GetByteCount(item.Body.GetRawText()) > 8192) throw new InventoryFault(400, "InvalidEvent");
            // P0 has no executable operations. Keep informational event facts only.
            if (item.EventType is not ("AgentConnected" or "InventoryApplied" or "InventoryApplyFailed" or "Diagnostic")) throw new InventoryFault(409, "UnsupportedCapability");
            var json = ControlJson.Serialize(item); var hash = ControlProtocol.Hash(json);
            if (item.EventSeq <= host.AckAgentEventSeq)
            {
                var old = await db.AgentEvents.FindAsync(host.HostId, item.EventSeq);
                if (old == null || old.Sha256 != hash) throw new InventoryFault(409, "OperationConflict");
                continue;
            }
            if (item.EventSeq != host.AckAgentEventSeq + 1) throw new InventoryFault(409, "StateConflict");
            db.AgentEvents.Add(new InventoryEvent { HostId = host.HostId, EventSeq = item.EventSeq, Json = json, Sha256 = hash, CreatedAtUtc = clock.GetUtcNow() });
            host.AckAgentEventSeq = item.EventSeq;
        }
        host.LastSeenAtUtc = clock.GetUtcNow(); return new EventAck(host.AckAgentEventSeq);
    });

    public async Task<InventoryHost> RequireAsync(AgentIdentity identity, Guid epoch, string? connectionId = null)
    {
        await credentials.EnsureActiveAsync(identity);
        connections.Require(identity.HostId, epoch, connectionId);
        var host = await store.HostAsync(identity.HostId);
        if (host.ConnectionEpoch != epoch || epoch == Guid.Empty || host.RecoveryRequired || host.ConnectionState is not (HostConnectionState.Reconciling or HostConnectionState.Online)) throw new InventoryFault(409, "StateConflict");
        if (host.LastSeenAtUtc == null || clock.GetUtcNow() - host.LastSeenAtUtc > TimeSpan.FromSeconds(30))
        {
            connections.RevokeHost(host.HostId);
            throw new InventoryFault(503, "HostOffline");
        }
        return host;
    }
    public async Task DisconnectedAsync(AgentIdentity identity, string connectionId)
    {
        await store.WriteAsync(async () =>
        {
            if (connections.Remove(identity.HostId, connectionId))
            {
                var host = await store.HostAsync(identity.HostId); host.ConnectionState = host.Enabled ? HostConnectionState.Offline : HostConnectionState.Disabled; host.ConnectionEpoch = null;
            }
            return true;
        });
    }
    private async Task ValidateRuntimeAsync(Guid hostId, ServerRuntimeSnapshot[] values)
    {
        if (values == null || values.Length > 32 || values.Select(v => v.ServerId).Distinct().Count() != values.Length) throw new InventoryFault(400, "InvalidInput");
        var host = await store.HostAsync(hostId);
        var previous = ControlJson.Deserialize<ServerRuntimeSnapshot[]>(host.RuntimeSnapshotJson).ToDictionary(x => x.ServerId);
        foreach (var value in values)
        {
            var server = await db.Servers.AsNoTracking().SingleOrDefaultAsync(s => s.ServerId == value.ServerId && s.HostId == hostId) ?? throw new InventoryFault(403, "HostScopeMismatch");
            if (value.ConfigRevision < 0 || value.ConfigRevision > server.DesiredRevision || value.StateRevision < 0 || !Enum.IsDefined(value.ProcessState) || !Enum.IsDefined(value.Readiness) || value.CurrentSessionId == Guid.Empty) throw new InventoryFault(400, "InvalidInput");
            if (previous.TryGetValue(value.ServerId, out var old) && (old.StateRevision > value.StateRevision || old.StateRevision == value.StateRevision && old != value)) throw new InventoryFault(409, "StateConflict");
        }
    }
    private static void ValidateResources(HostResources resources)
    {
        if (resources?.Roots == null || resources.Executables == null || resources.Roots.Length > 128 || resources.Executables.Length > 128) throw new InventoryFault(400, "InvalidInput");
        if (resources.Roots.Select(x => x.Id).Distinct().Count() != resources.Roots.Length || resources.Executables.Select(x => x.Id).Distinct().Count() != resources.Executables.Length) throw new InventoryFault(400, "InvalidInput");
        foreach (var root in resources.Roots)
            if (root.Id == Guid.Empty || string.IsNullOrWhiteSpace(root.DisplayName) || root.DisplayName.Length > 128 || string.IsNullOrWhiteSpace(root.Path) || root.Path.Length > 1024) throw new InventoryFault(400, "InvalidInput");
        foreach (var executable in resources.Executables)
            if (executable.Id == Guid.Empty || !Enum.IsDefined(executable.Kind) || string.IsNullOrWhiteSpace(executable.DisplayName) || executable.DisplayName.Length > 128 || string.IsNullOrWhiteSpace(executable.Path) || executable.Path.Length > 1024) throw new InventoryFault(400, "InvalidInput");
    }
}
