using AstralRecordControl.Contracts;
using Microsoft.Extensions.Hosting;

namespace AstralRecordControl.Agent;

public sealed class AgentScheduleService(AgentConfiguration configuration, AgentStore store, IRunnerClient runner,
    AgentOperationHandler operations, AgentProcessControlGate processGate, TimeProvider? clock = null) : BackgroundService
{
    private readonly TimeProvider clock = clock ?? TimeProvider.System;
    private readonly SemaphoreSlim tickGate = new(1, 1);
    private readonly Guid localEpoch = Guid.NewGuid();

    public async Task RefreshHeldReportsAsync(CancellationToken cancellationToken = default)
    {
        if (!configuration.EnableSchedules || !processGate.Enabled) return;
        await tickGate.WaitAsync(cancellationToken);
        try
        {
            var holds = (await runner.GetSnapshotAsync(cancellationToken)).Holds;
            foreach (var (_, report) in store.ReadScheduleReports(nonterminalOnly: true))
            {
                var hold = holds.SingleOrDefault(h => h.ReservationId == report.ReservationId);
                if (hold is null) continue;
                if (hold.OwnerWorkflowId != report.WorkflowId || report.Fence is long prior && prior != hold.Fence)
                    throw new AgentFault(ErrorCode.RecoveryRequired);
                if (report.Fence is null)
                    store.SaveScheduleReport(report with { Status = ScheduleOccurrenceStatus.Running, Fence = hold.Fence,
                        ReservedResourceKeys = hold.ResourceKeys, UpdatedAtUtc = clock.GetUtcNow() });
            }
        }
        finally { tickGate.Release(); }
    }

    public async Task<ScheduleOccurrenceReport> RunNowAsync(RunScheduleNowRequest request, Guid activeEpoch, CancellationToken cancellationToken)
    {
        if (request.ConnectionEpoch == Guid.Empty || request.ConnectionEpoch != activeEpoch || request.OperationId == Guid.Empty)
            throw new AgentFault(ErrorCode.StateConflict);
        if (!configuration.EnableSchedules || !processGate.Enabled) throw new AgentFault(ErrorCode.UnsupportedCapability);
        await tickGate.WaitAsync(cancellationToken);
        try
        {
            var (inventoryRevision, definitions) = store.GetAcknowledgedScheduleDefinitions();
            if (inventoryRevision == 0 || inventoryRevision != store.GetState().AppliedInventoryRevision) throw new AgentFault(ErrorCode.StaleRevision);
            var definition = definitions.SingleOrDefault(s => s.ScheduleId == request.ScheduleId && s.Revision == request.Revision && s.Enabled)
                ?? throw new AgentFault(ErrorCode.StaleRevision);
            var previous = store.GetScheduleReport(request.OperationId);
            if (previous is not null)
            {
                if (previous.ScheduleId != request.ScheduleId || previous.Revision != request.Revision || !previous.Manual) throw new AgentFault(ErrorCode.OperationConflict);
                return previous;
            }
            var report = NewReport(definition, inventoryRevision, clock.GetUtcNow(), manual: true, request.OperationId);
            store.ClaimScheduleOccurrence(definition, report);
            await ProcessAsync(definition, report, cancellationToken);
            return store.GetScheduleReport(request.OperationId)!;
        }
        finally { tickGate.Release(); }
    }

    public async Task TickAsync(CancellationToken cancellationToken = default)
    {
        if (!configuration.EnableSchedules || !processGate.Enabled) return;
        await tickGate.WaitAsync(cancellationToken);
        try
        {
            var now = clock.GetUtcNow();
            var (inventoryRevision, definitions) = store.GetAcknowledgedScheduleDefinitions();
            foreach (var definition in definitions.Where(s => s.Enabled && inventoryRevision == store.GetState().AppliedInventoryRevision))
            {
                var after = store.GetScheduleCursor(definition.ScheduleId, definition.Revision) ?? AgentSchedulePlanner.InitialCursor(definition, now);
                foreach (var due in AgentSchedulePlanner.DueBetween(definition, after, now))
                {
                    var id = ScheduleIdentity.Occurrence(definition.ScheduleId, definition.Revision, due);
                    var report = NewReport(definition, inventoryRevision, due, manual: false, id);
                    if (now - due > TimeSpan.FromSeconds(60)) report = report with { Status = ScheduleOccurrenceStatus.Skipped, ErrorCode = "DeadlineExpired" };
                    store.ClaimScheduleOccurrence(definition, report);
                }
                store.AdvanceScheduleCursor(definition.ScheduleId, definition.Revision, now);
            }
            foreach (var (definition, report) in store.ReadScheduleReports(nonterminalOnly: true))
            {
                if (report.Status == ScheduleOccurrenceStatus.NeedsAttention) continue;
                try { await ProcessAsync(definition, report, cancellationToken); }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { throw; }
                catch (Exception exception) when (exception is AgentFault or IOException or ArgumentException or InvalidOperationException)
                {
                    // The persisted report and Runner gate are rechecked on the next tick.
                }
            }
        }
        finally { tickGate.Release(); }
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(5), clock);
        do
        {
            try { await TickAsync(stoppingToken); }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception exception) when (exception is AgentFault or IOException or ArgumentException or InvalidOperationException) { }
        }
        while (await timer.WaitForNextTickAsync(stoppingToken));
    }

    private ScheduleOccurrenceReport NewReport(ScheduleDefinition definition, long inventoryRevision, DateTimeOffset scheduledAtUtc, bool manual, Guid operationId) => new()
    {
        ScheduleId = definition.ScheduleId, HostId = definition.HostId, ServerId = definition.ServerId!.Value,
        Revision = definition.Revision, ScheduledAtUtc = scheduledAtUtc, Manual = manual, OperationId = operationId,
        WorkflowId = ScheduleIdentity.Uuid5(operationId, "workflow"), ReservationId = ScheduleIdentity.Uuid5(operationId, "reservation"),
        AppliedInventoryRevision = inventoryRevision, DefinitionSha256 = ControlProtocol.Hash(ControlJson.Serialize(definition)),
        Status = ScheduleOccurrenceStatus.Pending, UpdatedAtUtc = clock.GetUtcNow()
    };

    private async Task ProcessAsync(ScheduleDefinition definition, ScheduleOccurrenceReport report, CancellationToken cancellationToken)
    {
        if (report.Status == ScheduleOccurrenceStatus.Pending && clock.GetUtcNow() - report.ScheduledAtUtc > TimeSpan.FromSeconds(60) && !report.Manual)
        {
            store.SaveScheduleReport(report with { Status = ScheduleOccurrenceStatus.Skipped, ErrorCode = "DeadlineExpired", UpdatedAtUtc = clock.GetUtcNow() });
            return;
        }
        ResourceHold hold;
        try
        {
            hold = await runner.AcquireHoldAsync(new AcquireHoldRequest(report.ReservationId, report.WorkflowId,
                ReservationKeys(definition), HoldMode.Exclusive), cancellationToken);
        }
        catch (AgentFault fault) when (fault.Code == ErrorCode.ReservationConflict && report.Fence is null)
        {
            store.SaveScheduleReport(report with { Status = ScheduleOccurrenceStatus.Skipped, ErrorCode = "ReservationConflict", UpdatedAtUtc = clock.GetUtcNow() });
            return;
        }
        catch (AgentFault fault) when (fault.Code == ErrorCode.ReservationConflict && report.Fence is not null)
        {
            var holds = (await runner.GetSnapshotAsync(cancellationToken)).Holds;
            if (holds.Any(h => h.ReservationId == report.ReservationId)) throw;
            var allSteps = report.Steps.Length == definition.Template.Steps.Length && report.Steps.All(s => OperationValidation.IsTerminal(s.Status));
            if (!allSteps) throw new AgentFault(ErrorCode.RecoveryRequired);
            var failed = report.Steps.FirstOrDefault(s => s.Status != OperationStatus.Succeeded);
            store.SaveScheduleReport(report with { Status = failed is null ? ScheduleOccurrenceStatus.Succeeded : ScheduleOccurrenceStatus.Failed,
                ErrorCode = failed?.ErrorCode, UpdatedAtUtc = clock.GetUtcNow() });
            return;
        }
        if (report.Fence is long expected && expected != hold.Fence) throw new AgentFault(ErrorCode.RecoveryRequired);
        report = store.SaveScheduleReport(report with
        {
            Status = ScheduleOccurrenceStatus.Running, Fence = hold.Fence, ReservedResourceKeys = hold.ResourceKeys,
            NextStepNotBeforeUtc = report.NextStepNotBeforeUtc ?? clock.GetUtcNow().AddSeconds(definition.Template.Steps[0].DelaySeconds),
            UpdatedAtUtc = clock.GetUtcNow()
        });
        for (var index = 0; index < definition.Template.Steps.Length; index++)
        {
            var step = definition.Template.Steps[index];
            if (report.NextStepNotBeforeUtc is DateTimeOffset notBefore && clock.GetUtcNow() < notBefore) return;
            var childId = ScheduleIdentity.Child(report.WorkflowId, step.StepKey, 1);
            var prior = report.Steps.SingleOrDefault(s => s.OperationId == childId);
            if (prior?.Status == OperationStatus.Succeeded) continue;
            if (prior?.Status is OperationStatus.Failed or OperationStatus.Rejected or OperationStatus.Expired or OperationStatus.Cancelled)
            {
                await FinishAsync(report, ScheduleOccurrenceStatus.Failed, prior.ErrorCode, hold, cancellationToken);
                return;
            }
            var runnerSnapshot = await runner.GetSnapshotAsync(cancellationToken);
            var runtime = runnerSnapshot.Servers.SingleOrDefault(s => s.ServerId == report.ServerId);
            if (runtime is null) { await FinishAsync(report, ScheduleOccurrenceStatus.Failed, "StateConflict", hold, cancellationToken); return; }
            var inventory = store.GetAppliedInventory();
            var server = inventory.Servers.SingleOrDefault(s => s.ServerId == report.ServerId);
            if (server is null || server.Revision != runtime.ConfigRevision)
            {
                await FinishAsync(report, ScheduleOccurrenceStatus.Failed, "StaleRevision", hold, cancellationToken); return;
            }
            var payload = step.Kind == OperationKind.CreateBackup
                ? ControlJson.Element(FileOperationValidation.Read<CreateBackupPayload>(step.Payload) with { BackupId = ScheduleIdentity.Uuid5(childId, "backup") })
                : step.Payload;
            var command = new OperationCommand
            {
                OperationId = childId, HostId = configuration.HostId, ServerId = report.ServerId, WorkflowId = report.WorkflowId,
                ReservationId = report.ReservationId, ReservationFence = hold.Fence, Kind = step.Kind, Payload = payload,
                ConfigRevision = server.Revision, ExpectedStateRevision = runtime.StateRevision, ExpectedServerSessionId = runtime.CurrentSessionId,
                StartBeforeUtc = clock.GetUtcNow().AddSeconds(60), ExecutionTimeoutSeconds = 3600
            };
            var json = ControlJson.Serialize(command);
            var hash = ControlProtocol.Hash(json);
            if (prior is null)
            {
                var added = new ScheduleStepReport(step.StepKey, 1, childId, OperationStatus.Accepted, null);
                store.RegisterScheduleChild(report.OperationId, added);
                report = store.SaveScheduleReport(report with { Steps = [.. report.Steps, added], NextStepNotBeforeUtc = null, UpdatedAtUtc = clock.GetUtcNow() });
            }
            ExecutionSnapshot result;
            try
            {
                if (store.GetOperation(childId) is null)
                    result = await operations.DispatchAsync(new DispatchOperationRequest(ControlProtocol.Version, localEpoch, childId, json, hash), localEpoch, cancellationToken);
                else result = await operations.QueryAsync(new AgentExecutionRequest(localEpoch, childId), localEpoch, cancellationToken);
            }
            catch (AgentFault fault) when (fault.Code is ErrorCode.StateConflict or ErrorCode.StaleRevision or ErrorCode.DeadlineExpired or ErrorCode.UnsupportedCapability or ErrorCode.ReservationConflict)
            {
                report = store.SaveScheduleReport(report with
                {
                    Steps = report.Steps.Select(s => s.OperationId == childId ? s with { Status = OperationStatus.Rejected, ErrorCode = fault.Code.ToString() } : s).ToArray(),
                    UpdatedAtUtc = clock.GetUtcNow()
                });
                await FinishAsync(report, ScheduleOccurrenceStatus.Failed, fault.Code.ToString(), hold, cancellationToken);
                return;
            }
            report = store.SaveScheduleReport(report with
            {
                Steps = report.Steps.Select(s => s.OperationId == childId ? s with { Status = result.Status, ErrorCode = result.ErrorCode } : s).ToArray(),
                UpdatedAtUtc = clock.GetUtcNow()
            });
            if (result.Status == OperationStatus.NeedsAttention)
            {
                store.SaveScheduleReport(report with { Status = ScheduleOccurrenceStatus.NeedsAttention, ErrorCode = result.ErrorCode, UpdatedAtUtc = clock.GetUtcNow() });
                return;
            }
            if (!result.IsTerminal) return;
            if (result.Status != OperationStatus.Succeeded)
            {
                await FinishAsync(report, ScheduleOccurrenceStatus.Failed, result.ErrorCode ?? result.Status.ToString(), hold, cancellationToken);
                return;
            }
            if (index + 1 < definition.Template.Steps.Length)
                report = store.SaveScheduleReport(report with { NextStepNotBeforeUtc = clock.GetUtcNow().AddSeconds(definition.Template.Steps[index + 1].DelaySeconds), UpdatedAtUtc = clock.GetUtcNow() });
        }
        await FinishAsync(report, ScheduleOccurrenceStatus.Succeeded, null, hold, cancellationToken);
    }

    private async Task FinishAsync(ScheduleOccurrenceReport report, ScheduleOccurrenceStatus status, string? error, ResourceHold hold, CancellationToken cancellationToken)
    {
        await runner.ReleaseHoldAsync(new ReleaseHoldRequest(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence), cancellationToken);
        store.SaveScheduleReport(report with { Status = status, ErrorCode = error, UpdatedAtUtc = clock.GetUtcNow() });
    }

    private string[] ReservationKeys(ScheduleDefinition definition)
    {
        var keys = new HashSet<string>(StringComparer.Ordinal) { "server:" + definition.ServerId!.Value.ToString("D") };
        if (definition.Template.Steps.Any(s => s.Kind is OperationKind.CreateBackup or OperationKind.PruneBackups))
        {
            var inventory = store.GetAppliedInventory();
            foreach (var step in definition.Template.Steps.Where(s => s.Kind is OperationKind.CreateBackup or OperationKind.PruneBackups))
            {
                var profileId = step.Kind == OperationKind.CreateBackup
                    ? FileOperationValidation.Read<CreateBackupPayload>(step.Payload).BackupProfileId
                    : FileOperationValidation.Read<PruneBackupsPayload>(step.Payload).BackupProfileId;
                var profile = inventory.BackupProfiles.Select(p => ControlJson.Deserialize<BackupProfile>(p.GetRawText()))
                    .SingleOrDefault(p => p.BackupProfileId == profileId && p.ServerId == definition.ServerId) ?? throw new AgentFault(ErrorCode.StaleRevision);
                keys.Add($"directory:{configuration.HostId:D}:{profile.DestinationRootId:D}:control-backups/{definition.ServerId.Value:N}");
            }
        }
        return keys.Order(StringComparer.Ordinal).ToArray();
    }
}
