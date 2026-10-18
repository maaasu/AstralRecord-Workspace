using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Operations;

public sealed class ReservationService(ControlDbContext db, InventoryStore store, IAgentOperationTransport agents, IConfiguration config, TimeProvider clock)
{
    public bool Enabled => config.GetValue<bool>("Control:EnableProcessControl");
    private DateTimeOffset Now => clock.GetUtcNow();
    public static string ServerKey(Guid id) => $"server:{id:D}";
    public static string EnvironmentKey(Guid id) => $"environment:{id:D}";
    public static string DirectoryKey(Guid host, Guid root, string path) => $"directory:{host:D}:{root:D}:{path.Trim('/').Replace('\\', '/').ToLowerInvariant()}";

    public async Task<GateLease> AcquireAsync(ReservationAcquireRequest request, CancellationToken token = default)
    {
        if (!Enabled) throw new InventoryFault(409, "UnsupportedCapability");
        if (request.WorkflowId == Guid.Empty || request.ResourceKeys == null || request.ResourceKeys.Length == 0 || request.ResourceKeys.Length > 128 || !Enum.IsDefined(request.Mode)) throw new InventoryFault(400, "InvalidInput");
        var resources = request.ResourceKeys.Select(x => x.Trim().ToLowerInvariant()).Order(StringComparer.Ordinal).ToArray();
        if (resources.Any(x => x.Length > 1024 || x.Length == 0) || resources.Distinct(StringComparer.Ordinal).Count() != resources.Length) throw new InventoryFault(400, "InvalidInput");
        if (request.AdapterId != null && request.AdapterId is not ("01" or "02" or "03" or "16" or "17")) throw new InventoryFault(400, "InvalidInput");
        if (request.RunReference is { Length: > 1024 } || request.RunReference?.Any(char.IsControl) == true || request.ServerConditions == null || request.ServerConditions.Any(x => x.ServerId == Guid.Empty || !Enum.IsDefined(x.RequiredState))) throw new InventoryFault(400, "InvalidInput");
        resources = await ExpandServerResourcesAsync(resources);
        var hostKeys = await ResolveHostsAsync(resources);
        if (hostKeys.Count == 0) throw new InventoryFault(400, "InvalidInput");
        if (request.ServerConditions.Any(x => !resources.Contains(ServerKey(x.ServerId), StringComparer.Ordinal))) throw new InventoryFault(400, "InvalidInput");
        var lease = await store.WriteAsync(async () =>
        {
            var existingWorkflow = await db.Workflows.FindAsync(request.WorkflowId);
            if (existingWorkflow != null)
            {
                if (existingWorkflow.RequestJson != ControlJson.Serialize(request) || existingWorkflow.Status is WorkflowStatus.Succeeded or WorkflowStatus.Failed or WorkflowStatus.Cancelled or WorkflowStatus.RecoveryRequired) throw new InventoryFault(409, "ReservationConflict");
                var existingLease = await GetAsync(request.WorkflowId);
                if (existingLease.Status is ReservationStatus.Released or ReservationStatus.RecoveryRequired || !existingLease.ResourceKeys.SequenceEqual(resources, StringComparer.Ordinal)) throw new InventoryFault(409, "ReservationConflict");
                return existingLease;
            }
            foreach (var hostId in hostKeys.Keys)
            {
                var host = await db.Hosts.AsNoTracking().SingleAsync(x => x.HostId == hostId);
                var capabilities = ControlJson.Deserialize<string[]>(host.CapabilitiesJson);
                if (!host.Enabled || host.RecoveryRequired || host.ConnectionState != HostConnectionState.Online || host.LastSeenAtUtc == null || Now - host.LastSeenAtUtc > TimeSpan.FromSeconds(30)) throw new InventoryFault(503, "HostOffline");
                if (!capabilities.Contains("P1.Operations") || !capabilities.Contains("P1.LegacyGate")) throw new InventoryFault(409, "UnsupportedCapability");
            }
            var rows = await db.Reservations.ToArrayAsync();
            foreach (var key in resources)
                if (rows.Any(row => row.State != ReservationStatus.Released && Conflicts(key, row.ResourceKey))) throw new InventoryFault(409, "ReservationConflict");
            var reservationId = Guid.NewGuid();
            db.Workflows.Add(new ControlWorkflow { WorkflowId = request.WorkflowId, Kind = request.AdapterId == null ? "SingleOperation" : "Maintenance", Status = WorkflowStatus.Reserving, RequestJson = ControlJson.Serialize(request), ResourceKeysJson = ControlJson.Serialize(resources), CreatedAtUtc = Now, UpdatedAtUtc = Now });
            db.ReservationLeases.Add(new ControlReservationLease { ReservationId = reservationId, WorkflowId = request.WorkflowId, ResourceKeysJson = ControlJson.Serialize(resources), AdapterId = request.AdapterId, RunReference = request.RunReference, CreatedAtUtc = Now });
            foreach (var key in resources)
            {
                var row = rows.SingleOrDefault(x => x.ResourceKey == key);
                if (row == null) { row = new ControlReservation { ResourceKey = key }; db.Reservations.Add(row); }
                row.ReservationId = reservationId; row.OwnerWorkflowId = request.WorkflowId; row.Mode = request.Mode; row.State = ReservationStatus.Preparing;
                row.HostId = hostKeys.SingleOrDefault(x => x.Value.Contains(key)).Key is var host && host != Guid.Empty ? host : null;
                row.Fence = checked(row.Fence + 1); row.CreatedAtUtc = Now; row.ReleasedAtUtc = null;
            }
            return new GateLease { WorkflowId = request.WorkflowId, ReservationId = reservationId, ResourceKeys = resources, Status = ReservationStatus.Preparing, AdapterId = request.AdapterId, RunReference = request.RunReference };
        });
        if (lease.Status == ReservationStatus.Prepared) return lease;
        var fences = new List<HostReservationFence>();
        try
        {
            var beforeSnapshots = new Dictionary<Guid, RunnerSnapshot>();
            foreach (var hostId in hostKeys.Keys.Order())
            {
                var snapshot = await agents.SnapshotAsync(hostId, token);
                beforeSnapshots[hostId] = snapshot;
                await ValidateServerConditionsAsync(request.ServerConditions, hostId, snapshot);
            }
            foreach (var (hostId, keys) in hostKeys.OrderBy(x => x.Key))
            {
                var prepared = await agents.PrepareAsync(hostId, new AcquireHoldRequest(lease.ReservationId, request.WorkflowId, keys, request.Mode), token);
                if (prepared.Hold.ReservationId != lease.ReservationId || prepared.Hold.OwnerWorkflowId != request.WorkflowId || prepared.Hold.Fence < 1 || !prepared.Hold.ResourceKeys.Order(StringComparer.Ordinal).SequenceEqual(keys.Order(StringComparer.Ordinal))) throw new InventoryFault(409, "RecoveryRequired");
                fences.Add(new HostReservationFence(hostId, prepared.Hold.Fence));
                await PersistFenceAsync(lease.ReservationId, hostId, prepared.Hold.Fence);
                await ValidateServerConditionsAsync(request.ServerConditions, hostId, prepared.Snapshot);
                foreach (var condition in request.ServerConditions)
                {
                    var old = beforeSnapshots[hostId].Servers.SingleOrDefault(x => x.ServerId == condition.ServerId);
                    var current = prepared.Snapshot.Servers.SingleOrDefault(x => x.ServerId == condition.ServerId);
                    if (old != null && current != null && (current.StateRevision <= old.StateRevision || current.CurrentSessionId != old.CurrentSessionId || current.ProcessState != old.ProcessState || current.ConfigRevision != old.ConfigRevision)) throw new InventoryFault(409, "StateConflict");
                }
            }
            await store.WriteAsync(async () =>
            {
                var workflow = await db.Workflows.SingleAsync(x => x.WorkflowId == request.WorkflowId);
                workflow.Status = WorkflowStatus.Running; workflow.UpdatedAtUtc = Now;
                var history = await db.ReservationLeases.SingleAsync(x => x.ReservationId == lease.ReservationId);
                history.Status = ReservationStatus.Prepared; history.PreparedOnce = true; history.FencesJson = ControlJson.Serialize(fences.ToArray());
                foreach (var fence in fences)
                    foreach (var row in await db.Reservations.Where(x => x.ReservationId == lease.ReservationId && x.HostId == fence.HostId).ToArrayAsync()) { row.Fence = fence.Fence; row.State = ReservationStatus.Prepared; }
                foreach (var row in await db.Reservations.Where(x => x.ReservationId == lease.ReservationId && x.HostId == null).ToArrayAsync()) row.State = ReservationStatus.Prepared;
                return true;
            });
            return lease with { Status = ReservationStatus.Prepared, Fences = fences.ToArray() };
        }
        catch
        {
            await ReconcilePreparationFailureAsync(lease.ReservationId, request.WorkflowId, hostKeys);
            throw;
        }
    }

    private async Task PersistFenceAsync(Guid reservationId, Guid hostId, long fence) => await store.WriteAsync(async () =>
    {
        var history = await db.ReservationLeases.SingleAsync(x => x.ReservationId == reservationId);
        var fences = ControlJson.Deserialize<HostReservationFence[]>(history.FencesJson).Where(x => x.HostId != hostId).Append(new HostReservationFence(hostId, fence)).OrderBy(x => x.HostId).ToArray();
        history.FencesJson = ControlJson.Serialize(fences);
        foreach (var row in await db.Reservations.Where(x => x.ReservationId == reservationId && x.HostId == hostId).ToArrayAsync()) row.Fence = fence;
        return true;
    });

    private async Task ReconcilePreparationFailureAsync(Guid reservationId, Guid workflowId, Dictionary<Guid, string[]> hostKeys)
    {
        await MarkRecoveryAsync(workflowId, reservationId);
        try
        {
            var held = new List<HostReservationFence>();
            foreach (var (hostId, keys) in hostKeys.OrderBy(x => x.Key))
            {
                var snapshot = await agents.SnapshotAsync(hostId, CancellationToken.None);
                var matches = snapshot.Holds.Where(x => x.ReservationId == reservationId).ToArray();
                if (matches.Length > 1 || matches.Any(x => x.OwnerWorkflowId != workflowId || x.Fence < 1 || !x.ResourceKeys.Order(StringComparer.Ordinal).SequenceEqual(keys.Order(StringComparer.Ordinal)))) return;
                if (matches.Length == 1)
                {
                    var fence = new HostReservationFence(hostId, matches[0].Fence);
                    await PersistFenceAsync(reservationId, hostId, fence.Fence);
                    held.Add(fence);
                }
            }
            if (await db.Operations.AnyAsync(x => x.ReservationId == reservationId)) return;
            foreach (var fence in held) await agents.ReleaseAsync(fence.HostId, new ReleaseHoldRequest(reservationId, workflowId, fence.Fence), CancellationToken.None);
            await CompleteInspectedAbortAsync(reservationId, workflowId);
        }
        catch { /* Unknown remote state retains RecoveryRequired and all central resources. */ }
    }

    private async Task CompleteInspectedAbortAsync(Guid reservationId, Guid workflowId) => await store.WriteAsync(async () =>
    {
        foreach (var row in await db.Reservations.Where(x => x.ReservationId == reservationId).ToArrayAsync()) { row.State = ReservationStatus.Released; row.ReleasedAtUtc = Now; }
        var history = await db.ReservationLeases.SingleAsync(x => x.ReservationId == reservationId);
        history.Status = ReservationStatus.Released; history.ReleasedAtUtc = Now;
        var workflow = await db.Workflows.SingleAsync(x => x.WorkflowId == workflowId);
        workflow.Status = WorkflowStatus.Failed; workflow.RecoveryRequired = false; workflow.UpdatedAtUtc = Now;
        return true;
    });

    public async Task<GateLease> GetAsync(Guid workflowId)
    {
        var history = await db.ReservationLeases.AsNoTracking().SingleOrDefaultAsync(x => x.WorkflowId == workflowId) ?? throw new InventoryFault(404, "NotFound");
        return Lease(history);
    }

    public async Task<GateLease> GetByReservationAsync(Guid reservationId)
    {
        var history = await db.ReservationLeases.AsNoTracking().SingleOrDefaultAsync(x => x.ReservationId == reservationId) ?? throw new InventoryFault(404, "NotFound");
        return Lease(history);
    }

    private static GateLease Lease(ControlReservationLease history) => new()
    {
        WorkflowId = history.WorkflowId, ReservationId = history.ReservationId,
        ResourceKeys = ControlJson.Deserialize<string[]>(history.ResourceKeysJson),
        Fences = ControlJson.Deserialize<HostReservationFence[]>(history.FencesJson),
        Status = history.Status, AdapterId = history.AdapterId, RunReference = history.RunReference
    };

    public async Task ValidateAsync(Guid reservationId, GateValidateRequest request, CancellationToken token = default)
    {
        var lease = await GetByReservationAsync(reservationId);
        RequireLease(lease, request.WorkflowId, request.ExpectedFences, request.RunReference);
        var workflow = await db.Workflows.AsNoTracking().SingleAsync(x => x.WorkflowId == request.WorkflowId);
        var original = ControlJson.Deserialize<ReservationAcquireRequest>(workflow.RequestJson);
        if (request.StepKey is not ("Resume" or "Deploy" or "Restore" or "Seed" or "WaitForStartup" or "Publish" or "Preview" or "Commit")) throw new InventoryFault(400, "InvalidInput");
        var expectedHostKeys = await ResolveHostsAsync(lease.ResourceKeys);
        var snapshots = new Dictionary<Guid, RunnerSnapshot>();
        foreach (var fence in lease.Fences)
        {
            var snapshot = await agents.SnapshotAsync(fence.HostId, token);
            if (!expectedHostKeys.TryGetValue(fence.HostId, out var hostKeys) || !snapshot.Holds.Any(x => x.ReservationId == reservationId && x.OwnerWorkflowId == request.WorkflowId && x.Fence == fence.Fence && x.ResourceKeys.Order(StringComparer.Ordinal).SequenceEqual(hostKeys.Order(StringComparer.Ordinal)))) throw new InventoryFault(409, "RecoveryRequired");
            snapshots[fence.HostId] = snapshot;
        }
        if (request.StepKey == "Resume") return;
        if (request.StepKey == "WaitForStartup" && lease.AdapterId is not ("01" or "16")) throw new InventoryFault(409, "ReservationConflict");
        if (request.StepKey == "Publish" && lease.AdapterId is ("01" or "16") && workflow.Status != WorkflowStatus.WaitingForStartup) throw new InventoryFault(409, "StateConflict");
        foreach (var condition in original.ServerConditions)
        {
            var server = await db.Servers.AsNoTracking().SingleOrDefaultAsync(x => x.ServerId == condition.ServerId) ?? throw new InventoryFault(404, "NotFound");
            var snapshot = snapshots[server.HostId];
            var actual = snapshot.Servers.SingleOrDefault(x => x.ServerId == condition.ServerId) ?? throw new InventoryFault(409, "StateConflict");
            if (request.StepKey == "WaitForStartup" && actual.ProcessState is not (ProcessState.Stopped or ProcessState.Running)) throw new InventoryFault(409, "StateConflict");
            var required = request.StepKey == "Publish" ? ProcessRequirement.Running : condition.RequiredState;
            if (request.StepKey != "WaitForStartup" && actual.ProcessState != (required == ProcessRequirement.Running ? ProcessState.Running : ProcessState.Stopped)) throw new InventoryFault(409, "StateConflict");
            if (!snapshot.Holds.Any(x => x.ReservationId == reservationId && x.OwnerWorkflowId == request.WorkflowId && lease.Fences.Any(f => f.HostId == server.HostId && f.Fence == x.Fence))) throw new InventoryFault(409, "RecoveryRequired");
        }
        if (request.StepKey is "WaitForStartup" or "Publish" && lease.AdapterId is ("01" or "16"))
            await store.WriteAsync(async () =>
            {
                var state = await db.Workflows.SingleAsync(x => x.WorkflowId == request.WorkflowId);
                state.Status = request.StepKey == "WaitForStartup" ? WorkflowStatus.WaitingForStartup : WorkflowStatus.Running;
                state.UpdatedAtUtc = Now;
                return true;
            });
    }

    public async Task ReleaseAsync(Guid reservationId, GateReleaseRequest request, CancellationToken token = default)
    {
        var lease = await GetByReservationAsync(reservationId);
        RequireLease(lease, request.WorkflowId, request.ExpectedFences, request.RunReference);
        var pending = await db.Operations.AsNoTracking().Where(x => x.ReservationId == reservationId).ToArrayAsync();
        if (pending.Any(x => !OperationValidation.IsTerminal(x.Status))) throw new InventoryFault(409, "RecoveryRequired");
        try
        {
            foreach (var fence in lease.Fences.OrderBy(x => x.HostId)) await agents.ReleaseAsync(fence.HostId, new ReleaseHoldRequest(reservationId, request.WorkflowId, fence.Fence), token);
            await store.WriteAsync(async () =>
            {
                foreach (var row in await db.Reservations.Where(x => x.ReservationId == reservationId).ToArrayAsync()) { row.State = ReservationStatus.Released; row.ReleasedAtUtc = Now; }
                var history = await db.ReservationLeases.SingleAsync(x => x.ReservationId == reservationId);
                history.Status = ReservationStatus.Released; history.ReleasedAtUtc = Now;
                var workflow = await db.Workflows.SingleAsync(x => x.WorkflowId == request.WorkflowId);
                workflow.Status = request.Outcome == GateOutcome.Succeeded ? WorkflowStatus.Succeeded : WorkflowStatus.Failed; workflow.UpdatedAtUtc = Now;
                return true;
            });
        }
        catch { await MarkRecoveryAsync(request.WorkflowId, reservationId); throw; }
    }

    public async Task ReleaseInspectedAsync(Guid reservationId, Guid workflowId, HostReservationFence[] fences, GateOutcome outcome, CancellationToken token = default)
    {
        var lease = await GetByReservationAsync(reservationId);
        if (lease.WorkflowId != workflowId || lease.Status != ReservationStatus.RecoveryRequired || lease.Fences.Length != fences.Length || lease.Fences.Any(x => !fences.Contains(x))) throw new InventoryFault(409, "ReservationConflict");
        var workflow = await db.Workflows.AsNoTracking().SingleAsync(x => x.WorkflowId == workflowId);
        if (workflow.Kind != "SingleOperation") throw new InventoryFault(409, "UnsupportedCapability");
        var children = await db.Operations.AsNoTracking().Where(x => x.WorkflowId == workflowId).ToArrayAsync();
        if (children.Length == 0 || children.Any(x => !OperationValidation.IsTerminal(x.Status))) throw new InventoryFault(409, "RecoveryRequired");
        await store.WriteAsync(async () =>
        {
            foreach (var row in await db.Reservations.Where(x => x.ReservationId == reservationId).ToArrayAsync()) row.State = ReservationStatus.Prepared;
            (await db.ReservationLeases.SingleAsync(x => x.ReservationId == reservationId)).Status = ReservationStatus.Prepared;
            return true;
        });
        await ReleaseAsync(reservationId, new GateReleaseRequest(workflowId, fences, outcome, lease.RunReference ?? ""), token);
    }

    public async Task RecoverMaintenancePreparationAsync(Guid workflowId, WorkflowRecoveryAction action, CancellationToken token = default)
    {
        if (action is not (WorkflowRecoveryAction.Resume or WorkflowRecoveryAction.AbortAfterInspection)) throw new InventoryFault(409, "UnsupportedCapability");
        var lease = await GetAsync(workflowId);
        var history = await db.ReservationLeases.AsNoTracking().SingleAsync(x => x.ReservationId == lease.ReservationId);
        var workflow = await db.Workflows.AsNoTracking().SingleAsync(x => x.WorkflowId == workflowId);
        if (workflow.Kind != "Maintenance" || history.PreparedOnce || lease.Status != ReservationStatus.RecoveryRequired || await db.Operations.AnyAsync(x => x.ReservationId == lease.ReservationId)) throw new InventoryFault(409, "RecoveryRequired");
        var original = ControlJson.Deserialize<ReservationAcquireRequest>(workflow.RequestJson);
        var hostKeys = await ResolveHostsAsync(lease.ResourceKeys);
        var held = new List<HostReservationFence>();
        foreach (var (hostId, keys) in hostKeys.OrderBy(x => x.Key))
        {
            var snapshot = await agents.SnapshotAsync(hostId, token);
            var matches = snapshot.Holds.Where(x => x.ReservationId == lease.ReservationId).ToArray();
            if (matches.Length > 1 || matches.Any(x => x.OwnerWorkflowId != workflowId || x.Fence < 1 || !x.ResourceKeys.Order(StringComparer.Ordinal).SequenceEqual(keys.Order(StringComparer.Ordinal)))) throw new InventoryFault(409, "RecoveryRequired");
            if (matches.Length == 1)
            {
                await PersistFenceAsync(lease.ReservationId, hostId, matches[0].Fence);
                held.Add(new HostReservationFence(hostId, matches[0].Fence));
            }
            if (action == WorkflowRecoveryAction.Resume) await ValidateServerConditionsAsync(original.ServerConditions, hostId, snapshot);
        }
        if (action == WorkflowRecoveryAction.AbortAfterInspection)
        {
            foreach (var fence in held) await agents.ReleaseAsync(fence.HostId, new ReleaseHoldRequest(lease.ReservationId, workflowId, fence.Fence), token);
            await CompleteInspectedAbortAsync(lease.ReservationId, workflowId);
            return;
        }
        foreach (var (hostId, keys) in hostKeys.OrderBy(x => x.Key))
        {
            if (held.Any(x => x.HostId == hostId)) continue;
            var prepared = await agents.PrepareAsync(hostId, new AcquireHoldRequest(lease.ReservationId, workflowId, keys, original.Mode), token);
            if (prepared.Hold.ReservationId != lease.ReservationId || prepared.Hold.OwnerWorkflowId != workflowId || prepared.Hold.Fence < 1 || !prepared.Hold.ResourceKeys.Order(StringComparer.Ordinal).SequenceEqual(keys.Order(StringComparer.Ordinal))) throw new InventoryFault(409, "RecoveryRequired");
            await PersistFenceAsync(lease.ReservationId, hostId, prepared.Hold.Fence);
            await ValidateServerConditionsAsync(original.ServerConditions, hostId, prepared.Snapshot);
            held.Add(new HostReservationFence(hostId, prepared.Hold.Fence));
        }
        await store.WriteAsync(async () =>
        {
            var current = await db.ReservationLeases.SingleAsync(x => x.ReservationId == lease.ReservationId);
            current.Status = ReservationStatus.Prepared; current.PreparedOnce = true;
            foreach (var row in await db.Reservations.Where(x => x.ReservationId == lease.ReservationId).ToArrayAsync()) row.State = ReservationStatus.Prepared;
            var state = await db.Workflows.SingleAsync(x => x.WorkflowId == workflowId);
            state.Status = WorkflowStatus.Running; state.RecoveryRequired = false; state.UpdatedAtUtc = Now;
            return true;
        });
    }

    public async Task MarkRecoveryAsync(Guid workflowId, Guid reservationId) => await store.WriteAsync(async () =>
    {
        var workflow = await db.Workflows.SingleAsync(x => x.WorkflowId == workflowId);
        workflow.Status = WorkflowStatus.RecoveryRequired; workflow.RecoveryRequired = true; workflow.UpdatedAtUtc = Now;
        foreach (var row in await db.Reservations.Where(x => x.ReservationId == reservationId).ToArrayAsync()) row.State = ReservationStatus.RecoveryRequired;
        (await db.ReservationLeases.SingleAsync(x => x.ReservationId == reservationId)).Status = ReservationStatus.RecoveryRequired;
        return true;
    });

    private static void RequireLease(GateLease lease, Guid workflowId, HostReservationFence[] fences, string runReference)
    {
        if (lease.Status != ReservationStatus.Prepared || lease.WorkflowId != workflowId || (lease.RunReference ?? "") != runReference || fences == null || lease.Fences.Length != fences.Length || lease.Fences.Any(f => !fences.Contains(f))) throw new InventoryFault(409, "ReservationConflict");
    }

    private async Task<Dictionary<Guid, string[]>> ResolveHostsAsync(string[] keys)
    {
        var hostKeys = new Dictionary<Guid, List<string>>();
        foreach (var key in keys)
        {
            if (key.StartsWith("environment:", StringComparison.Ordinal))
            {
                if (!Guid.TryParse(key[12..], out var id) || !await db.Environments.AnyAsync(x => x.EnvironmentId == id)) throw new InventoryFault(400, "InvalidInput");
            }
            else if (key.StartsWith("server:", StringComparison.Ordinal))
            {
                if (!Guid.TryParse(key[7..], out var id)) throw new InventoryFault(400, "InvalidInput");
                var server = await db.Servers.AsNoTracking().SingleOrDefaultAsync(x => x.ServerId == id) ?? throw new InventoryFault(404, "NotFound");
                if (!hostKeys.TryGetValue(server.HostId, out var list)) hostKeys[server.HostId] = list = [];
                list.Add(key);
            }
            else if (key.StartsWith("directory:", StringComparison.Ordinal))
            {
                var parts = key.Split(':', 4);
                if (parts.Length != 4 || !Guid.TryParse(parts[1], out var host) || !Guid.TryParse(parts[2], out _) || !ControlProtocol.IsRelativePath(parts[3])) throw new InventoryFault(400, "InvalidInput");
                if (!await db.Hosts.AnyAsync(x => x.HostId == host)) throw new InventoryFault(404, "NotFound");
                if (!hostKeys.TryGetValue(host, out var list)) hostKeys[host] = list = [];
                list.Add(key);
            }
            else throw new InventoryFault(400, "InvalidInput");
        }
        return hostKeys.ToDictionary(x => x.Key, x => x.Value.ToArray());
    }

    private async Task<string[]> ExpandServerResourcesAsync(string[] keys)
    {
        var expanded = new HashSet<string>(keys, StringComparer.Ordinal);
        foreach (var key in keys.Where(x => x.StartsWith("server:", StringComparison.Ordinal)))
        {
            if (!Guid.TryParse(key[7..], out var id)) throw new InventoryFault(400, "InvalidInput");
            var server = await db.Servers.AsNoTracking().SingleOrDefaultAsync(x => x.ServerId == id) ?? throw new InventoryFault(404, "NotFound");
            var config = ControlJson.Deserialize<ServerConfig>(server.DesiredConfigJson);
            expanded.Add(DirectoryKey(server.HostId, config.ManagedRootId, config.ServerDirectory));
            if (server.EnvironmentId.HasValue) expanded.Add(EnvironmentKey(server.EnvironmentId.Value));
        }
        return expanded.Order(StringComparer.Ordinal).ToArray();
    }

    private async Task ValidateServerConditionsAsync(GateServerCondition[] conditions, Guid hostId, RunnerSnapshot snapshot)
    {
        foreach (var condition in conditions)
        {
            var server = await db.Servers.AsNoTracking().SingleAsync(x => x.ServerId == condition.ServerId);
            if (server.HostId != hostId) continue;
            var actual = snapshot.Servers.SingleOrDefault(x => x.ServerId == condition.ServerId) ?? throw new InventoryFault(409, "StateConflict");
            if (actual.ProcessState != (condition.RequiredState == ProcessRequirement.Stopped ? ProcessState.Stopped : ProcessState.Running) || actual.ConfigRevision != server.AppliedRevision || server.AppliedRevision != server.DesiredRevision) throw new InventoryFault(409, "StateConflict");
            if (condition.RequiredState == ProcessRequirement.Running && actual.CurrentSessionId == null) throw new InventoryFault(409, "StateConflict");
        }
    }

    private static bool Conflicts(string left, string right)
    {
        if (left == right) return true;
        if (!left.StartsWith("directory:", StringComparison.Ordinal) || !right.StartsWith("directory:", StringComparison.Ordinal)) return false;
        return left.StartsWith(right + "/", StringComparison.Ordinal) || right.StartsWith(left + "/", StringComparison.Ordinal);
    }
}
