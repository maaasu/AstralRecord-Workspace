using System.Text.Json;
using System.Security.Cryptography;
using System.Text;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Operations;

public sealed class ControlOperationsService(ControlDbContext db, InventoryStore store, ReservationService reservations, IAgentOperationTransport agents, TimeProvider clock, IConfiguration? configuration = null, Transfers.TransferRelay? transfers = null)
{
    private DateTimeOffset Now => clock.GetUtcNow();

    public async Task<ControlOperation> CreateAsync(Guid serverId, ServerOperationRequest request, Guid idempotencyKey, CancellationToken token = default)
    {
        if (!reservations.Enabled) throw new InventoryFault(409, "UnsupportedCapability");
        var dataOperation = OperationValidation.IsP2Kind(request.Kind);
        if (dataOperation && configuration?.GetValue<bool>("Control:EnableFileControl") != true) throw new InventoryFault(409, "UnsupportedCapability");
        if (idempotencyKey == Guid.Empty || (!OperationValidation.IsP1Kind(request.Kind) && !dataOperation) || request.WorkflowId.HasValue != request.ReservationId.HasValue) throw new InventoryFault(400, "InvalidInput");
        if (dataOperation)
        {
            try { FileOperationValidation.ValidatePayload(request.Kind, request.Payload); }
            catch (ArgumentException) { throw new InventoryFault(400, "InvalidInput"); }
        }
        var requestJson = ControlJson.Serialize(request);
        var requestHash = ControlProtocol.Hash($"ServerOperation:{serverId:D}:{requestJson}");
        var replay = await db.Operations.AsNoTracking().SingleOrDefaultAsync(x => x.IdempotencyKey == idempotencyKey);
        if (replay != null)
        {
            if (replay.RequestSha256 != requestHash) throw new InventoryFault(409, "OperationConflict");
            if (replay.CommandJson.Length == 0 && replay.FinishedAtUtc.HasValue) throw new InventoryFault(410, "HistoryExpired");
            return await RefreshAsync(replay.OperationId, token);
        }
        var server = await db.Servers.AsNoTracking().SingleOrDefaultAsync(x => x.ServerId == serverId) ?? throw new InventoryFault(404, "NotFound");
        if (server.AppliedRevision != request.ConfigRevision || server.DesiredRevision != request.ConfigRevision || request.ConfigRevision < 1) throw new InventoryFault(409, "StaleRevision");
        var config = ControlJson.Deserialize<ServerConfig>(server.DesiredConfigJson);
        Guid? backupDestination = null;
        if (request.Kind is OperationKind.ImportArtifact or OperationKind.ProvisionServer)
        {
            var artifact = request.Kind == OperationKind.ImportArtifact
                ? ControlJson.Deserialize<ImportArtifactPayload>(request.Payload.GetRawText()).Artifact
                : ControlJson.Deserialize<ProvisionServerPayload>(request.Payload.GetRawText()).Artifact;
            var transfer = transfers == null ? throw new InventoryFault(409, "UnsupportedCapability") : await transfers.GetAsync(artifact.TransferId, null, token);
            if (transfer.ServerId != serverId || transfer.DestinationHostId != server.HostId) throw new InventoryFault(403, "HostScopeMismatch");
            if (transfer.Status != TransferStatus.Verified || transfer.SizeBytes != artifact.SizeBytes || transfer.Sha256 != artifact.Sha256) throw new InventoryFault(409, "HashMismatch");
        }
        if (!dataOperation)
        {
            var operationHost = await db.Hosts.AsNoTracking().SingleAsync(h => h.HostId == server.HostId);
            var processCapabilities = ControlJson.Deserialize<string[]>(operationHost.CapabilitiesJson);
            if (!ServerProcessSupport.HasKindCapability(config.Kind, processCapabilities) || config.DependencyReadiness != DependencyReadiness.Running || config.DependsOnServerIds.Length != 0) throw new InventoryFault(409, "UnsupportedCapability");
        }
        if (dataOperation)
        {
            var host = await db.Hosts.AsNoTracking().SingleAsync(h => h.HostId == server.HostId);
            var capabilities = ControlJson.Deserialize<string[]>(host.CapabilitiesJson);
            if (!capabilities.Contains("P2.Files") || (IsBackupKind(request.Kind) && !capabilities.Contains("P2.Backups"))) throw new InventoryFault(409, "UnsupportedCapability");
            if (request.Kind == OperationKind.CreateBackup)
            {
                var payload = ControlJson.Deserialize<CreateBackupPayload>(request.Payload.GetRawText());
                backupDestination = (await store.AppliedBackupProfileAsync(serverId, payload.BackupProfileId, payload.ProfileRevision)).DestinationRootId;
            }
            if (request.Kind == OperationKind.PruneBackups)
                backupDestination = (await store.AppliedBackupProfileAsync(serverId, ControlJson.Deserialize<PruneBackupsPayload>(request.Payload.GetRawText()).BackupProfileId)).DestinationRootId;
            Guid? backupId = request.Kind switch
            {
                OperationKind.RestoreBackup => ControlJson.Deserialize<RestoreBackupPayload>(request.Payload.GetRawText()).BackupId,
                OperationKind.SetBackupProtection => ControlJson.Deserialize<SetBackupProtectionPayload>(request.Payload.GetRawText()).BackupId,
                OperationKind.DeleteBackup => ControlJson.Deserialize<DeleteBackupPayload>(request.Payload.GetRawText()).BackupId,
                OperationKind.ExportFiles => ControlJson.Deserialize<ExportFilesPayload>(request.Payload.GetRawText()).BackupId,
                _ => null
            };
            if (backupId.HasValue)
            {
                var backup = (await agents.ListBackupsAsync(server.HostId, serverId, token, backupId: backupId)).Rows.SingleOrDefault(b => b.BackupId == backupId) ?? throw new InventoryFault(404, "NotFound");
                if (backup.DestinationRootId == Guid.Empty) throw new InventoryFault(409, "RecoveryRequired");
                backupDestination = backup.DestinationRootId;
            }
            if (request.Kind == OperationKind.ExportFiles)
                await ValidateExportAsync(server, idempotencyKey, request.Payload, token);
        }
        var before = await agents.SnapshotAsync(server.HostId, token);
        var oldState = before.Servers.SingleOrDefault(x => x.ServerId == serverId) ?? throw new InventoryFault(409, "StateConflict");
        if (oldState.StateRevision != request.ExpectedStateRevision || oldState.CurrentSessionId != request.ExpectedServerSessionId || oldState.ConfigRevision != request.ConfigRevision) throw new InventoryFault(409, "StateConflict");
        ValidateState(request.Kind, oldState);
        var keys = new List<string> { ReservationService.ServerKey(serverId), ReservationService.DirectoryKey(server.HostId, config.ManagedRootId, config.ServerDirectory) };
        if (backupDestination.HasValue) keys.Add(ReservationService.DirectoryKey(server.HostId, backupDestination.Value, "control-backups/" + serverId.ToString("N")));
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
                OperationId = dataOperation ? idempotencyKey : Guid.NewGuid(), HostId = server.HostId, ServerId = serverId, WorkflowId = workflowId,
                ReservationId = lease.ReservationId, ReservationFence = fence.Fence, Kind = request.Kind,
                ConfigRevision = request.ConfigRevision, ExpectedStateRevision = current.StateRevision,
                ExpectedServerSessionId = current.CurrentSessionId,
                StartBeforeUtc = request.StartBeforeUtc ?? Now.AddSeconds(60),
                ExecutionTimeoutSeconds = request.ExecutionTimeoutSeconds ?? (dataOperation ? 3600 : request.Kind switch { OperationKind.Start => 900, OperationKind.Stop => 120, OperationKind.Restart => 1020, _ => 30 }),
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
                await PrepareArtifactAsync(row, token);
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

    public async Task<ControlOperation> ApplyExecutionAsync(Guid operationId, ExecutionSnapshot execution)
    {
        var applied = await store.WriteAsync(async () =>
    {
        var row = await db.Operations.SingleOrDefaultAsync(x => x.OperationId == operationId) ?? throw new InventoryFault(404, "NotFound");
        if (execution.OperationId != operationId || execution.CommandSha256 != row.CommandSha256) throw new InventoryFault(409, "OperationConflict");
        // A retired operation remains as an identity/hash tombstone for Agent replay.
        if (await db.Workflows.AnyAsync(w => w.WorkflowId == row.WorkflowId && w.HiddenAtUtc != null)) return row;
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
        if (applied.CommandJson.Length != 0) await FinalizeArtifactAsync(applied);
        return applied;
    }

    public async Task<ControlOperation> GetAsync(Guid operationId)
    {
        var row = await db.Operations.AsNoTracking().SingleOrDefaultAsync(x => x.OperationId == operationId) ?? throw new InventoryFault(404, "NotFound");
        if (row.CommandJson.Length == 0 && row.FinishedAtUtc.HasValue) throw new InventoryFault(410, "HistoryExpired");
        return row;
    }

    public async Task<ControlOperation> RefreshAsync(Guid operationId, CancellationToken token = default)
    {
        var row = await GetAsync(operationId);
        if (OperationValidation.IsTerminal(row.Status)) { await FinalizeArtifactAsync(row); return row; }
        ExecutionSnapshot execution;
        try
        {
            // Reuse the immutable command: Agent and Runner both deduplicate this ID/hash.
            // This also repairs a crash after the Queued transaction committed.
            if (row.Status == OperationStatus.Queued) await PrepareArtifactAsync(row, token);
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
        var dataChild = await db.Operations.AsNoTracking().SingleOrDefaultAsync(x => x.WorkflowId == workflowId && (x.Kind == OperationKind.RestoreBackup || x.Kind == OperationKind.CreateBackup));
        if (dataChild != null && request.Action == WorkflowRecoveryAction.Resume && dataChild.Status == OperationStatus.NeedsAttention)
        {
            if (request.Action != WorkflowRecoveryAction.Resume || dataChild.Status != OperationStatus.NeedsAttention) throw new InventoryFault(409, "RecoveryRequired");
            var resumed = await agents.ResumeDataAsync(dataChild.HostId, dataChild.OperationId, request.Reason, token);
            return await ApplyExecutionAsync(dataChild.OperationId, resumed);
        }
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
        if (OperationValidation.IsP2Kind(kind))
        {
            if (kind is OperationKind.ListFiles or OperationKind.ReadFile or OperationKind.ExportFiles) return;
            if (kind is OperationKind.CreateBackup or OperationKind.RestoreBackup && state.ProcessState == ProcessState.Running && state.CurrentSessionId != null) return;
            if (state.ProcessState != ProcessState.Stopped || state.CurrentSessionId != null) throw new InventoryFault(409, "StateConflict");
            return;
        }
        if (kind == OperationKind.Start && (state.ProcessState != ProcessState.Stopped || state.CurrentSessionId != null)) throw new InventoryFault(409, "StateConflict");
        if (kind == OperationKind.Stop && state.ProcessState == ProcessState.Stopped && state.CurrentSessionId is null) return;
        if (kind != OperationKind.Start && (state.ProcessState != ProcessState.Running || state.CurrentSessionId is null)) throw new InventoryFault(409, "StateConflict");
    }

    private static bool IsBackupKind(OperationKind kind) => kind is OperationKind.CreateBackup or OperationKind.RestoreBackup or OperationKind.PruneBackups or OperationKind.SetBackupProtection or OperationKind.DeleteBackup;

    private async Task PrepareArtifactAsync(ControlOperation row, CancellationToken token)
    {
        if (row.Kind == OperationKind.ExportFiles)
        {
            var server = await store.ServerAsync(row.ServerId!.Value);
            await ValidateExportAsync(server, row.OperationId, ControlJson.Deserialize<OperationCommand>(row.CommandJson).Payload, token);
            return;
        }
        if (row.Kind is not (OperationKind.ImportArtifact or OperationKind.ProvisionServer)) return;
        var command = ControlJson.Deserialize<OperationCommand>(row.CommandJson);
        var artifact = row.Kind == OperationKind.ImportArtifact ? ControlJson.Deserialize<ImportArtifactPayload>(command.Payload.GetRawText()).Artifact : ControlJson.Deserialize<ProvisionServerPayload>(command.Payload.GetRawText()).Artifact;
        if (transfers == null) throw new InventoryFault(409, "UnsupportedCapability");
        try { await transfers.GetVerifiedForConsumeAsync(artifact.TransferId, row.HostId, row.ServerId!.Value, row.OperationId, token); }
        catch (InventoryFault fault) when (fault.Code is "OperationConflict" or "StateConflict" or "HostScopeMismatch")
        {
            await store.WriteAsync(async () =>
            {
                var current = await db.Operations.SingleAsync(x => x.OperationId == row.OperationId);
                if (current.Status == OperationStatus.Queued) { current.Status = OperationStatus.Rejected; current.StatusVersion++; current.ErrorCode = fault.Code; current.FinishedAtUtc = Now; }
                return true;
            });
            throw;
        }
    }

    private async Task ValidateExportAsync(InventoryServer server, Guid operationId, JsonElement payload, CancellationToken token)
    {
        var export = ControlJson.Deserialize<ExportFilesPayload>(payload.GetRawText());
        if (transfers == null) throw new InventoryFault(409, "UnsupportedCapability");
        var transfer = await transfers.GetAsync(export.TransferId, null, token);
        if (transfer.SourceHostId != server.HostId || transfer.ServerId != server.ServerId || transfer.OperationId != operationId) throw new InventoryFault(409, "OperationConflict");
        if (transfer.Status is not (TransferStatus.Created or TransferStatus.Uploading or TransferStatus.Verified)) throw new InventoryFault(409, "StateConflict");
    }

    private async Task FinalizeArtifactAsync(ControlOperation row)
    {
        if (row.Status != OperationStatus.Succeeded || row.Kind is not (OperationKind.ImportArtifact or OperationKind.ProvisionServer) || transfers == null) return;
        var payload = ControlJson.Deserialize<OperationCommand>(row.CommandJson).Payload;
        var artifact = row.Kind == OperationKind.ImportArtifact ? ControlJson.Deserialize<ImportArtifactPayload>(payload.GetRawText()).Artifact : ControlJson.Deserialize<ProvisionServerPayload>(payload.GetRawText()).Artifact;
        await transfers.FinalizeConsumedAsync(artifact.TransferId, row.OperationId, CancellationToken.None);
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
