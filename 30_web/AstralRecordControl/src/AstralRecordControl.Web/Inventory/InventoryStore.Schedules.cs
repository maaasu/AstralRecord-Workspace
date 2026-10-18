using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Inventory;

public sealed record ScheduleView(ScheduleDefinition Definition, long? AppliedRevision);

public sealed partial class InventoryStore
{
    public async Task<ScheduleView[]> SchedulesAsync(Guid? serverId = null)
    {
        var result = new List<ScheduleView>();
        foreach (var host in await db.Hosts.AsNoTracking().ToArrayAsync())
        {
            var desired = await db.InventoryVersions.AsNoTracking().SingleAsync(v => v.HostId == host.HostId && v.Revision == host.DesiredInventoryRevision);
            var applied = host.AppliedInventoryRevision == 0 ? null : await db.InventoryVersions.AsNoTracking().SingleAsync(v => v.HostId == host.HostId && v.Revision == host.AppliedInventoryRevision);
            var appliedDefinitions = applied == null ? [] : ControlJson.Deserialize<InventorySnapshot>(applied.SnapshotJson).Schedules.Select(ScheduleValidation.Read).ToArray();
            foreach (var definition in ControlJson.Deserialize<InventorySnapshot>(desired.SnapshotJson).Schedules.Select(ScheduleValidation.Read))
                if (!serverId.HasValue || definition.ServerId == serverId)
                    result.Add(new(definition, appliedDefinitions.SingleOrDefault(a => a.ScheduleId == definition.ScheduleId)?.Revision));
        }
        return result.OrderBy(v => v.Definition.ScheduleId).ToArray();
    }

    public async Task<ScheduleDefinition> SaveScheduleAsync(ScheduleDefinition definition, long? expectedRevision, Guid key) => await WriteAsync(async () =>
    {
        if (configuration?.GetValue<bool>("Control:EnableSchedules") != true || !ProcessControlEnabled) throw new InventoryFault(409, "UnsupportedCapability");
        ScheduleValidation.Validate(definition);
        var server = await ServerAsync(definition.ServerId!.Value);
        var host = await HostAsync(server.HostId);
        if (definition.HostId != host.HostId || !host.Enabled) throw new InventoryFault(400, "HostScopeMismatch");
        var capabilities = ControlJson.Deserialize<string[]>(host.CapabilitiesJson);
        if (!capabilities.Contains("P3.Schedules")) throw new InventoryFault(409, "UnsupportedCapability");
        var hash = ControlProtocol.Hash("Schedule:" + expectedRevision + ":" + ControlJson.Serialize(definition));
        var replay = await ReplayAsync(key, hash);
        var all = await SchedulesAsync();
        if (replay != null) return all.Single(s => s.Definition.ScheduleId == replay.EntityId).Definition;
        var old = all.SingleOrDefault(s => s.Definition.ScheduleId == definition.ScheduleId)?.Definition;
        if (expectedRevision == null)
        {
            if (old != null || definition.Revision != 1) throw new InventoryFault(409, "StaleRevision");
        }
        else if (old == null || old.HostId != definition.HostId || old.ServerId != definition.ServerId || old.Revision != expectedRevision || definition.Revision != checked(expectedRevision.Value + 1)) throw new InventoryFault(409, "StaleRevision");
        var version = await db.InventoryVersions.AsNoTracking().SingleAsync(v => v.HostId == host.HostId && v.Revision == host.DesiredInventoryRevision);
        var snapshot = ControlJson.Deserialize<InventorySnapshot>(version.SnapshotJson);
        foreach (var step in definition.Template.Steps)
        {
            if (step.Kind is OperationKind.CreateBackup or OperationKind.PruneBackups)
            {
                if (configuration?.GetValue<bool>("Control:EnableFileControl") != true || !capabilities.Contains("P2.Backups")) throw new InventoryFault(409, "UnsupportedCapability");
                var profileId = step.Kind == OperationKind.CreateBackup ? FileOperationValidation.Read<CreateBackupPayload>(step.Payload).BackupProfileId : FileOperationValidation.Read<PruneBackupsPayload>(step.Payload).BackupProfileId;
                var profile = snapshot.BackupProfiles.Select(p => ControlJson.Deserialize<BackupProfile>(p.GetRawText())).SingleOrDefault(p => p.BackupProfileId == profileId && p.ServerId == server.ServerId) ?? throw new InventoryFault(400, "InvalidInput");
                if (step.Kind == OperationKind.CreateBackup && profile.Revision != FileOperationValidation.Read<CreateBackupPayload>(step.Payload).ProfileRevision) throw new InventoryFault(409, "StaleRevision");
            }
        }
        SaveInventory(host, snapshot.Servers, schedules: snapshot.Schedules.Select(ScheduleValidation.Read).Where(s => s.ScheduleId != definition.ScheduleId).Append(definition).Select(ControlJson.Element).ToArray());
        SaveRequest(key, hash, definition.ScheduleId, "Schedule");
        return definition;
    });

    public async Task<ScheduleDefinition> AppliedScheduleAsync(Guid scheduleId)
    {
        var view = (await SchedulesAsync()).SingleOrDefault(s => s.Definition.ScheduleId == scheduleId) ?? throw new InventoryFault(404, "NotFound");
        if (view.AppliedRevision != view.Definition.Revision) throw new InventoryFault(409, "StaleRevision");
        return view.Definition;
    }

    public async Task<ScheduleDefinition> PrepareRunNowAsync(Guid scheduleId, Guid operationId) => await WriteAsync(async () =>
    {
        if (configuration?.GetValue<bool>("Control:EnableSchedules") != true || !ProcessControlEnabled || operationId == Guid.Empty) throw new InventoryFault(409, "UnsupportedCapability");
        var old = await db.ScheduleManualRequests.FindAsync(operationId);
        if (old != null && old.ScheduleId != scheduleId) throw new InventoryFault(409, "OperationConflict");
        if (old != null)
        {
            if (old.DefinitionJson.Length == 0) throw new InventoryFault(410, "HistoryExpired");
            return ControlJson.Deserialize<ScheduleDefinition>(old.DefinitionJson);
        }
        var definition = await AppliedScheduleAsync(scheduleId);
        if (!definition.Enabled) throw new InventoryFault(409, "UnsupportedCapability");
        var host = await HostAsync(definition.HostId);
        if (!host.Enabled || host.RecoveryRequired || host.ConnectionState != HostConnectionState.Online || !host.ConnectionEpoch.HasValue || host.LastSeenAtUtc == null || Now - host.LastSeenAtUtc > TimeSpan.FromSeconds(30)) throw new InventoryFault(503, "HostOffline");
        if (await db.InventoryRequests.AnyAsync(x => x.IdempotencyKey == operationId) || await db.Operations.AnyAsync(x => x.IdempotencyKey == operationId) || await db.ScheduleOccurrences.AnyAsync(x => x.OperationId == operationId)) throw new InventoryFault(409, "OperationConflict");
        db.ScheduleManualRequests.Add(new() { OperationId = operationId, ScheduleId = scheduleId, HostId = definition.HostId, Revision = definition.Revision, DefinitionJson = ControlJson.Serialize(definition), CreatedAtUtc = Now });
        return definition;
    });

    public async Task<ScheduleOccurrenceReport[]> ScheduleHistoryAsync(Guid scheduleId)
    {
        var rows = await db.ScheduleOccurrences.AsNoTracking().Where(r => r.ScheduleId == scheduleId && r.ReportJson != "").ToArrayAsync();
        return rows.OrderByDescending(r => r.UpdatedAtUtc).Take(100).Select(r => ControlJson.Deserialize<ScheduleOccurrenceReport>(r.ReportJson)).ToArray();
    }

    public async Task<ScheduleOccurrenceReport?> ManualReportAsync(Guid scheduleId, Guid operationId)
    {
        var request = await db.ScheduleManualRequests.AsNoTracking().SingleOrDefaultAsync(r => r.OperationId == operationId && r.ScheduleId == scheduleId) ?? throw new InventoryFault(404, "NotFound");
        var row = await db.ScheduleOccurrences.AsNoTracking().SingleOrDefaultAsync(r => r.OperationId == request.OperationId);
        if (row?.ReportJson == "" || request.DefinitionJson.Length == 0) throw new InventoryFault(410, "HistoryExpired");
        return row == null ? null : ControlJson.Deserialize<ScheduleOccurrenceReport>(row.ReportJson);
    }

    public async Task<ScheduleManualRequest> ManualRequestAsync(Guid scheduleId, Guid operationId) => await db.ScheduleManualRequests.AsNoTracking().SingleOrDefaultAsync(r => r.OperationId == operationId && r.ScheduleId == scheduleId) ?? throw new InventoryFault(404, "NotFound");

    public async Task RejectManualRequestAsync(Guid scheduleId, Guid operationId, string code) => await WriteAsync(async () =>
    {
        if (!Enum.TryParse<ErrorCode>(code, false, out var value) || !Enum.IsDefined(value)) throw new InventoryFault(400, "InvalidInput");
        var request = await db.ScheduleManualRequests.SingleAsync(r => r.OperationId == operationId && r.ScheduleId == scheduleId);
        if (!await db.ScheduleOccurrences.AnyAsync(r => r.OperationId == operationId)) request.RejectionCode = code;
        return true;
    });

    public async Task<ScheduleManualRequest[]> PendingManualRequestsAsync(Guid serverId)
    {
        var ids = (await SchedulesAsync(serverId)).Select(s => s.Definition.ScheduleId).ToArray();
        var requests = await db.ScheduleManualRequests.AsNoTracking().Where(r => ids.Contains(r.ScheduleId) && r.DefinitionJson != "").ToArrayAsync();
        var reported = (await db.ScheduleOccurrences.AsNoTracking().Where(r => ids.Contains(r.ScheduleId)).Select(r => r.OperationId).ToArrayAsync()).ToHashSet();
        return requests.Where(r => !reported.Contains(r.OperationId)).OrderByDescending(r => r.CreatedAtUtc).Take(100).ToArray();
    }

    // Caller owns the InventoryWriteGate transaction. Historical inventory remains the authority
    // when a disconnected Agent reports an occurrence from a previously applied definition.
    public async Task StageScheduleReportAsync(Guid hostId, ScheduleOccurrenceReport report)
    {
        if (report.HostId != hostId || report.ScheduleId == Guid.Empty || report.OperationId == Guid.Empty || report.ScheduledAtUtc.Offset != TimeSpan.Zero || report.UpdatedAtUtc.Offset != TimeSpan.Zero || report.UpdatedAtUtc > Now.AddMinutes(5) || report.ScheduledAtUtc > Now.AddMinutes(1) || !Enum.IsDefined(report.Status) || report.Steps == null || report.ReservedResourceKeys == null || report.Steps.Length > 16 || report.ReservedResourceKeys.Length > 8) throw new InventoryFault(400, "InvalidEvent");
        var host = await HostAsync(hostId);
        if (report.AppliedInventoryRevision > host.AppliedInventoryRevision || report.AppliedInventoryRevision < 1) throw new InventoryFault(409, "StaleRevision");
        var version = await db.InventoryVersions.AsNoTracking().SingleOrDefaultAsync(v => v.HostId == hostId && v.Revision == report.AppliedInventoryRevision) ?? throw new InventoryFault(409, "StaleRevision");
        var snapshot = ControlJson.Deserialize<InventorySnapshot>(version.SnapshotJson);
        var definition = snapshot.Schedules.Select(ScheduleValidation.Read).SingleOrDefault(d => d.ScheduleId == report.ScheduleId && d.Revision == report.Revision) ?? throw new InventoryFault(409, "OperationConflict");
        if (definition.ServerId != report.ServerId || ControlProtocol.Hash(ControlJson.Serialize(definition)) != report.DefinitionSha256 || report.WorkflowId != ScheduleIdentity.Uuid5(report.OperationId, "workflow") || report.ReservationId != ScheduleIdentity.Uuid5(report.OperationId, "reservation")) throw new InventoryFault(409, "OperationConflict");
        var config = snapshot.Servers.Single(s => s.ServerId == report.ServerId);
        var resourceKeys = new HashSet<string>(StringComparer.Ordinal) { Operations.ReservationService.ServerKey(report.ServerId), Operations.ReservationService.DirectoryKey(hostId, config.ManagedRootId, config.ServerDirectory) };
        foreach (var step in definition.Template.Steps.Where(s => s.Kind is OperationKind.CreateBackup or OperationKind.PruneBackups))
        {
            var profileId = step.Kind == OperationKind.CreateBackup ? FileOperationValidation.Read<CreateBackupPayload>(step.Payload).BackupProfileId : FileOperationValidation.Read<PruneBackupsPayload>(step.Payload).BackupProfileId;
            var profile = snapshot.BackupProfiles.Select(p => ControlJson.Deserialize<BackupProfile>(p.GetRawText())).SingleOrDefault(p => p.BackupProfileId == profileId && p.ServerId == report.ServerId) ?? throw new InventoryFault(409, "OperationConflict");
            resourceKeys.Add(Operations.ReservationService.DirectoryKey(hostId, profile.DestinationRootId, $"control-backups/{report.ServerId:N}"));
        }
        if (report.Fence.HasValue && (report.Fence.Value < 1 || !resourceKeys.SetEquals(report.ReservedResourceKeys)) || !report.Fence.HasValue && report.ReservedResourceKeys.Length != 0) throw new InventoryFault(409, "OperationConflict");
        if (report.Manual)
        {
            var manual = await db.ScheduleManualRequests.FindAsync(report.OperationId);
            if (manual == null || manual.HostId != hostId || manual.ScheduleId != report.ScheduleId || manual.Revision != report.Revision) throw new InventoryFault(409, "OperationConflict");
            manual.RejectionCode = null;
        }
        else if (!definition.Enabled || report.OperationId != ScheduleIdentity.Occurrence(report.ScheduleId, report.Revision, report.ScheduledAtUtc) || !ScheduleValidation.IsOccurrence(definition, report.ScheduledAtUtc)) throw new InventoryFault(409, "OperationConflict");
        foreach (var step in report.Steps)
        {
            if (!definition.Template.Steps.Any(s => s.StepKey == step.StepKey) || step.Attempt != 1 || step.OperationId != ScheduleIdentity.Child(report.WorkflowId, step.StepKey, step.Attempt) || !Enum.IsDefined(step.Status)) throw new InventoryFault(400, "InvalidEvent");
        }
        if (report.Steps.Select(s => s.StepKey).Distinct().Count() != report.Steps.Length) throw new InventoryFault(400, "InvalidEvent");
        var existing = db.ScheduleOccurrences.Local.SingleOrDefault(r => r.OperationId == report.OperationId) ?? await db.ScheduleOccurrences.FindAsync(report.OperationId);
        if (existing != null)
        {
            if (existing.ReportJson.Length == 0) return; // Retained immutable ID tombstone; ACK replay cannot resurrect expired content.
            var old = ControlJson.Deserialize<ScheduleOccurrenceReport>(existing.ReportJson);
            if (old.ScheduleId != report.ScheduleId || old.Revision != report.Revision || old.ScheduledAtUtc != report.ScheduledAtUtc || old.Manual != report.Manual || old.DefinitionSha256 != report.DefinitionSha256) throw new InventoryFault(409, "OperationConflict");
            if (old.UpdatedAtUtc > report.UpdatedAtUtc || IsScheduleTerminal(old.Status)) return;
            existing.ReportJson = ControlJson.Serialize(report); existing.UpdatedAtUtc = report.UpdatedAtUtc;
            existing.AckedAgentEventSeq = 0;
        }
        else db.ScheduleOccurrences.Add(new() { OperationId = report.OperationId, HostId = hostId, ScheduleId = report.ScheduleId, ReportJson = ControlJson.Serialize(report), UpdatedAtUtc = report.UpdatedAtUtc });
    }

    public static bool IsScheduleTerminal(ScheduleOccurrenceStatus status) => status is ScheduleOccurrenceStatus.Succeeded or ScheduleOccurrenceStatus.Failed or ScheduleOccurrenceStatus.Skipped;
}
