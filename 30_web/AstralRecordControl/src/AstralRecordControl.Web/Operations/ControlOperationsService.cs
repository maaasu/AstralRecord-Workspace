using System.Text.Json;
using System.Security.Cryptography;
using System.Text;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Operations;

public sealed class ControlOperationsService(ControlDbContext db, InventoryStore store, ReservationService reservations, IAgentOperationTransport agents, TimeProvider clock)
{
    private DateTimeOffset Now => clock.GetUtcNow();

    public async Task<ControlOperation> CreateAsync(Guid serverId, ServerOperationRequest request, Guid idempotencyKey, CancellationToken token = default)
    {
        if (!reservations.Enabled) throw new InventoryFault(409, "UnsupportedCapability");
        if (idempotencyKey == Guid.Empty || !OperationValidation.IsP1Kind(request.Kind) || request.WorkflowId.HasValue != request.ReservationId.HasValue) throw new InventoryFault(400, "InvalidInput");
        var requestJson = ControlJson.Serialize(request);
        var requestHash = ControlProtocol.Hash($"ServerOperation:{serverId:D}:{requestJson}");
        var replay = await db.Operations.AsNoTracking().SingleOrDefaultAsync(x => x.IdempotencyKey == idempotencyKey);
        if (replay != null)
        {
            if (replay.RequestSha256 != requestHash) throw new InventoryFault(409, "OperationConflict");
            return await RefreshAsync(replay.OperationId, token);
        }
        var server = await db.Servers.AsNoTracking().SingleOrDefaultAsync(x => x.ServerId == serverId) ?? throw new InventoryFault(404, "NotFound");
        if (server.AppliedRevision != request.ConfigRevision || server.DesiredRevision != request.ConfigRevision || request.ConfigRevision < 1) throw new InventoryFault(409, "StaleRevision");
        var config = ControlJson.Deserialize<ServerConfig>(server.DesiredConfigJson);
        if (config.Kind != ServerKind.Java || config.DependencyReadiness == DependencyReadiness.AstralReady) throw new InventoryFault(409, "UnsupportedCapability");
        var before = await agents.SnapshotAsync(server.HostId, token);
        var oldState = before.Servers.SingleOrDefault(x => x.ServerId == serverId) ?? throw new InventoryFault(409, "StateConflict");
        if (oldState.StateRevision != request.ExpectedStateRevision || oldState.CurrentSessionId != request.ExpectedServerSessionId || oldState.ConfigRevision != request.ConfigRevision) throw new InventoryFault(409, "StateConflict");
        ValidateState(request.Kind, oldState);
        var keys = new List<string> { ReservationService.ServerKey(serverId), ReservationService.DirectoryKey(server.HostId, config.ManagedRootId, config.ServerDirectory) };
        if (server.EnvironmentId.HasValue) keys.Add(ReservationService.EnvironmentKey(server.EnvironmentId.Value));
        var inherited = request.WorkflowId.HasValue;
        var workflowId = request.WorkflowId ?? Guid.NewGuid();
        var lease = inherited
            ? await reservations.GetByReservationAsync(request.ReservationId!.Value)
            : await reservations.AcquireAsync(new ReservationAcquireRequest { WorkflowId = workflowId, ResourceKeys = keys.ToArray(), Mode = HoldMode.Exclusive }, token);
        try
        {
            if (lease.WorkflowId != workflowId || lease.Status != ReservationStatus.Prepared || !lease.ResourceKeys.Contains(ReservationService.ServerKey(serverId))) throw new InventoryFault(409, "ReservationConflict");
            if (inherited)
            {
                var workflow = await db.Workflows.AsNoTracking().SingleAsync(x => x.WorkflowId == workflowId);
                var history = await db.ReservationLeases.AsNoTracking().SingleAsync(x => x.ReservationId == lease.ReservationId);
                var original = ControlJson.Deserialize<ReservationAcquireRequest>(workflow.RequestJson);
                if (request.Kind != OperationKind.Start || lease.AdapterId is not ("01" or "03" or "16") || workflow.Status != WorkflowStatus.WaitingForStartup || history.LastStepKey != "WaitForStartup" || !original.ServerConditions.Any(x => x.ServerId == serverId && x.RequiredState == ProcessRequirement.Stopped)) throw new InventoryFault(409, "ReservationConflict");
            }
            var fence = lease.Fences.SingleOrDefault(x => x.HostId == server.HostId);
            if (fence == null || lease.Status != ReservationStatus.Prepared) throw new InventoryFault(409, "RecoveryRequired");
            var after = await agents.SnapshotAsync(server.HostId, token);
            var current = after.Servers.SingleOrDefault(x => x.ServerId == serverId) ?? throw new InventoryFault(409, "RecoveryRequired");
            if ((!inherited && current.StateRevision <= oldState.StateRevision) || (inherited && current.StateRevision != oldState.StateRevision) || current.ProcessState != oldState.ProcessState || current.CurrentSessionId != oldState.CurrentSessionId || current.ConfigRevision != oldState.ConfigRevision) throw new InventoryFault(409, "StateConflict");
            if (!after.Holds.Any(x => x.ReservationId == lease.ReservationId && x.OwnerWorkflowId == workflowId && x.Fence == fence.Fence && x.ResourceKeys.Contains(ReservationService.ServerKey(serverId)))) throw new InventoryFault(409, "RecoveryRequired");
            var command = new OperationCommand
            {
                OperationId = Guid.NewGuid(), HostId = server.HostId, ServerId = serverId, WorkflowId = workflowId,
                ReservationId = lease.ReservationId, ReservationFence = fence.Fence, Kind = request.Kind,
                ConfigRevision = request.ConfigRevision, ExpectedStateRevision = current.StateRevision,
                ExpectedServerSessionId = current.CurrentSessionId,
                StartBeforeUtc = request.StartBeforeUtc ?? Now.AddSeconds(60),
                ExecutionTimeoutSeconds = request.ExecutionTimeoutSeconds ?? (request.Kind switch { OperationKind.Start => 900, OperationKind.Stop => 120, OperationKind.Restart => 1020, _ => 30 }),
                Payload = request.Payload
            };
            try { OperationValidation.Validate(command, server.HostId); }
            catch (ArgumentException) { throw new InventoryFault(400, "InvalidInput"); }
            if (command.StartBeforeUtc <= Now) throw new InventoryFault(409, "DeadlineExpired");
            var json = ControlJson.Serialize(command);
            var row = await store.WriteAsync(async () =>
            {
                var previous = await db.Operations.SingleOrDefaultAsync(x => x.IdempotencyKey == idempotencyKey);
                if (previous != null)
                {
                    if (previous.RequestSha256 != requestHash) throw new InventoryFault(409, "OperationConflict");
                    return previous;
                }
                var operation = new ControlOperation { OperationId = command.OperationId, IdempotencyKey = idempotencyKey, HostId = server.HostId, ServerId = serverId, WorkflowId = workflowId, ReservationId = lease.ReservationId, Kind = request.Kind, RequestSha256 = requestHash, CommandJson = json, CommandSha256 = ControlProtocol.Hash(json), Status = OperationStatus.Queued, CreatedAtUtc = Now };
                db.Operations.Add(operation); return operation;
            });
            if (row.OperationId != command.OperationId) return await RefreshAsync(row.OperationId, token);
            try
            {
                var execution = await agents.DispatchAsync(row.HostId, row.OperationId, row.CommandJson, row.CommandSha256, token);
                return await ApplyExecutionAsync(row.OperationId, execution);
            }
            catch { return await RefreshAsync(row.OperationId, token); }
        }
        catch
        {
            if (!inherited && !await db.Operations.AnyAsync(x => x.WorkflowId == workflowId))
            {
                try { await reservations.ReleaseAsync(lease.ReservationId, new GateReleaseRequest(workflowId, lease.Fences, GateOutcome.Failed, ""), token); }
                catch { await reservations.MarkRecoveryAsync(workflowId, lease.ReservationId); }
            }
            throw;
        }
    }

    public async Task<ControlOperation> ApplyExecutionAsync(Guid operationId, ExecutionSnapshot execution) => await store.WriteAsync(async () =>
    {
        var row = await db.Operations.SingleOrDefaultAsync(x => x.OperationId == operationId) ?? throw new InventoryFault(404, "NotFound");
        if (execution.OperationId != operationId || execution.CommandSha256 != row.CommandSha256) throw new InventoryFault(409, "OperationConflict");
        if (execution.StatusVersion <= row.StatusVersion) return row;
        if (OperationValidation.IsTerminal(row.Status) && execution.Status != row.Status) throw new InventoryFault(409, "RecoveryRequired");
        row.Status = execution.Status; row.StatusVersion = execution.StatusVersion; row.ErrorCode = execution.ErrorCode;
        row.ResultJson = execution.Result.GetRawText();
        if (execution.Status is OperationStatus.Accepted or OperationStatus.Running) row.AcceptedAtUtc ??= Now;
        if (execution.Status == OperationStatus.Running) row.StartedAtUtc ??= Now;
        if (OperationValidation.IsTerminal(execution.Status)) row.FinishedAtUtc = Now;
        if (execution.Status == OperationStatus.NeedsAttention)
        {
            var workflow = await db.Workflows.SingleAsync(x => x.WorkflowId == row.WorkflowId);
            workflow.Status = WorkflowStatus.RecoveryRequired; workflow.RecoveryRequired = true; workflow.UpdatedAtUtc = Now;
            foreach (var reservation in await db.Reservations.Where(x => x.ReservationId == row.ReservationId).ToArrayAsync()) reservation.State = ReservationStatus.RecoveryRequired;
            (await db.ReservationLeases.SingleAsync(x => x.ReservationId == row.ReservationId)).Status = ReservationStatus.RecoveryRequired;
        }
        return row;
    });

    public async Task<ControlOperation> GetAsync(Guid operationId) => await db.Operations.AsNoTracking().SingleOrDefaultAsync(x => x.OperationId == operationId) ?? throw new InventoryFault(404, "NotFound");

    public async Task<ControlOperation> RefreshAsync(Guid operationId, CancellationToken token = default)
    {
        var row = await GetAsync(operationId);
        if (OperationValidation.IsTerminal(row.Status)) return row;
        ExecutionSnapshot execution;
        try
        {
            // Reuse the immutable command: Agent and Runner both deduplicate this ID/hash.
            // This also repairs a crash after the Queued transaction committed.
            execution = row.Status == OperationStatus.Queued
                ? await agents.DispatchAsync(row.HostId, row.OperationId, row.CommandJson, row.CommandSha256, token)
                : await agents.QueryAsync(row.HostId, operationId, token);
        }
        catch (InventoryFault fault) when (fault.Code is "OperationConflict" or "RecoveryRequired")
        {
            await MarkAttentionAsync(operationId);
            return await GetAsync(operationId);
        }
        catch (HubException fault) when (fault.Message is "OperationConflict" or "RecoveryRequired")
        {
            await MarkAttentionAsync(operationId);
            return await GetAsync(operationId);
        }
        catch { return row; }
        return await ApplyExecutionAsync(operationId, execution);
    }

    public async Task<LogBatch> ReadLogsAsync(Guid serverId, ReadLogsRequest request, CancellationToken token = default)
    {
        if (request.ServerId != serverId || request.Limit is < 1 or > 200 || request.AfterSequence < 0) throw new InventoryFault(400, "InvalidInput");
        var server = await db.Servers.AsNoTracking().SingleOrDefaultAsync(x => x.ServerId == serverId) ?? throw new InventoryFault(404, "NotFound");
        return await agents.ReadLogsAsync(server.HostId, request, token);
    }

    public async Task<ControlOperation?> RecoverAsync(Guid workflowId, WorkflowRecoveryRequest request, CancellationToken token = default)
    {
        if (string.IsNullOrWhiteSpace(request.Reason) || request.Reason.Length > 1024 || request.Reason.Any(c => c is '\r' or '\n' or '\0')) throw new InventoryFault(400, "InvalidInput");
        var lease = await reservations.GetAsync(workflowId);
        if (lease.Status != ReservationStatus.RecoveryRequired) throw new InventoryFault(409, "StateConflict");
        var workflow = await db.Workflows.AsNoTracking().SingleAsync(x => x.WorkflowId == workflowId);
        if (workflow.Kind != "SingleOperation") throw new InventoryFault(409, "UnsupportedCapability");
        if (request.Action is WorkflowRecoveryAction.Resume or WorkflowRecoveryAction.AbortAfterInspection)
        {
            var children = await db.Operations.AsNoTracking().Where(x => x.WorkflowId == workflowId).ToArrayAsync();
            if (children.Length == 0 || children.Select(x => x.ServerId).Distinct().Count() != 1 || children[0].ServerId is not Guid inspectedServerId) throw new InventoryFault(409, "RecoveryRequired");
            foreach (var child in children)
                if (!OperationValidation.IsTerminal((await RefreshAsync(child.OperationId, token)).Status)) throw new InventoryFault(409, "RecoveryRequired");
            var inspectedServer = await db.Servers.AsNoTracking().SingleAsync(x => x.ServerId == inspectedServerId);
            var inspected = await agents.InspectRecoveryAsync(inspectedServer.HostId, inspectedServerId, token);
            if (inspected.Runtime.ServerId != inspectedServerId || inspected.RecoveryRequired ||
                (inspected.ProcessAlive && (!inspected.IdentityMatches || inspected.Runtime.ProcessState != ProcessState.Running || inspected.Runtime.CurrentSessionId != inspected.Session?.ServerSessionId)) ||
                (!inspected.ProcessAlive && (inspected.Runtime.ProcessState != ProcessState.Stopped || inspected.Runtime.CurrentSessionId != null))) throw new InventoryFault(409, "RecoveryRequired");
            await reservations.ReleaseInspectedAsync(lease.ReservationId, workflowId, lease.Fences, GateOutcome.Failed, token);
            return null;
        }
        var oldStop = await db.Operations.AsNoTracking().SingleOrDefaultAsync(x => x.WorkflowId == workflowId && (x.Kind == OperationKind.Stop || x.Kind == OperationKind.Restart)) ?? throw new InventoryFault(409, "RecoveryRequired");
        var serverId = oldStop.ServerId ?? throw new InventoryFault(409, "RecoveryRequired");
        var server = await db.Servers.AsNoTracking().SingleAsync(x => x.ServerId == serverId);
        var inspection = await agents.InspectRecoveryAsync(server.HostId, serverId, token);
        if (inspection.Runtime.ServerId != serverId) throw new InventoryFault(409, "RecoveryRequired");
        oldStop = await RefreshAsync(oldStop.OperationId, token);
        if (request.Action != WorkflowRecoveryAction.KillAfterInspection || !inspection.ProcessAlive || !inspection.IdentityMatches || inspection.Runtime.CurrentSessionId is null) throw new InventoryFault(409, "RecoveryRequired");
        if (oldStop.Status != OperationStatus.NeedsAttention) throw new InventoryFault(409, "StateConflict");
        var fence = lease.Fences.SingleOrDefault(x => x.HostId == server.HostId) ?? throw new InventoryFault(409, "RecoveryRequired");
        var operationId = ChildId(workflowId, "KillAfterInspection");
        var requestHash = ControlProtocol.Hash($"RecoveryKill:{workflowId:D}:{request.Reason}");
        var existing = await db.Operations.AsNoTracking().SingleOrDefaultAsync(x => x.OperationId == operationId);
        if (existing != null)
        {
            if (existing.RequestSha256 != requestHash) throw new InventoryFault(409, "OperationConflict");
            return await RefreshAsync(existing.OperationId, token);
        }
        var command = new OperationCommand
        {
            OperationId = operationId, HostId = server.HostId, ServerId = serverId, WorkflowId = workflowId,
            ReservationId = lease.ReservationId, ReservationFence = fence.Fence, Kind = OperationKind.Kill,
            ConfigRevision = server.AppliedRevision, ExpectedStateRevision = inspection.Runtime.StateRevision,
            ExpectedServerSessionId = inspection.Runtime.CurrentSessionId,
            StartBeforeUtc = Now.AddSeconds(60), ExecutionTimeoutSeconds = 30,
            Payload = ControlJson.Element(new { reason = request.Reason })
        };
        OperationValidation.Validate(command, server.HostId);
        var json = ControlJson.Serialize(command);
        var row = await store.WriteAsync(async () =>
        {
            var duplicate = await db.Operations.SingleOrDefaultAsync(x => x.OperationId == operationId);
            if (duplicate != null) return duplicate;
            var created = new ControlOperation { OperationId = operationId, IdempotencyKey = operationId, HostId = server.HostId, ServerId = serverId, WorkflowId = workflowId, ReservationId = lease.ReservationId, Kind = OperationKind.Kill, RequestSha256 = requestHash, CommandJson = json, CommandSha256 = ControlProtocol.Hash(json), Status = OperationStatus.Queued, CreatedAtUtc = Now };
            db.Operations.Add(created); return created;
        });
        if (row.CommandSha256 != ControlProtocol.Hash(json) || row.RequestSha256 != requestHash) throw new InventoryFault(409, "OperationConflict");
        return await RefreshAsync(row.OperationId, token);
    }

    private async Task MarkAttentionAsync(Guid operationId)
    {
        var row = await GetAsync(operationId);
        await store.WriteAsync(async () =>
        {
            var operation = await db.Operations.SingleAsync(x => x.OperationId == operationId);
            operation.Status = OperationStatus.NeedsAttention; operation.StatusVersion++;
            var workflow = await db.Workflows.SingleAsync(x => x.WorkflowId == row.WorkflowId);
            workflow.Status = WorkflowStatus.RecoveryRequired; workflow.RecoveryRequired = true;
            foreach (var reservation in await db.Reservations.Where(x => x.ReservationId == row.ReservationId).ToArrayAsync()) reservation.State = ReservationStatus.RecoveryRequired;
            (await db.ReservationLeases.SingleAsync(x => x.ReservationId == row.ReservationId)).Status = ReservationStatus.RecoveryRequired;
            return true;
        });
    }

    private static void ValidateState(OperationKind kind, ServerRuntimeSnapshot state)
    {
        if (kind == OperationKind.Start && (state.ProcessState != ProcessState.Stopped || state.CurrentSessionId != null)) throw new InventoryFault(409, "StateConflict");
        if (kind == OperationKind.Stop && state.ProcessState == ProcessState.Stopped && state.CurrentSessionId is null) return;
        if (kind != OperationKind.Start && (state.ProcessState != ProcessState.Running || state.CurrentSessionId is null)) throw new InventoryFault(409, "StateConflict");
    }

    private static Guid ChildId(Guid workflowId, string name)
    {
        var bytes = workflowId.ToByteArray();
        Array.Reverse(bytes, 0, 4); Array.Reverse(bytes, 4, 2); Array.Reverse(bytes, 6, 2);
        var hash = SHA1.HashData(bytes.Concat(Encoding.UTF8.GetBytes(name)).ToArray());
        var result = hash[..16]; result[6] = (byte)(result[6] & 0x0F | 0x50); result[8] = (byte)(result[8] & 0x3F | 0x80);
        Array.Reverse(result, 0, 4); Array.Reverse(result, 4, 2); Array.Reverse(result, 6, 2);
        return new Guid(result);
    }
}
