using System.Security.Cryptography;
using System.Text;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Notifications;

public sealed class NotificationService(ControlDbContext db, IDataProtectionProvider protection, TimeProvider clock, InventoryStore store)
{
    private readonly IDataProtector protector = protection.CreateProtector("AstralRecordControl.Web.NotificationDestination.v1");

    // Called inside InventoryStore.WriteAsync, before AgentEvents and ACK commit.
    public async Task StageAgentEventAsync(Guid hostId, AgentEvent item)
    {
        var eventId = StableId($"agent:{hostId:D}:{item.EventSeq}");
        if (db.NotificationEvents.Local.Any(x => x.EventId == eventId) || await db.NotificationEvents.AnyAsync(x => x.EventId == eventId)) return;
        string? kind = null;
        string? outcome = null;
        Guid? serverId = null;
        Guid? scheduleId = null;
        Guid? scheduleRunId = null;
        string? scheduleStepsJson = null;
        string? sourceKey = null;
        if (item.EventType == "OperationChanged" && item.OperationId is Guid operationId)
        {
            var operation = await db.Operations.SingleOrDefaultAsync(x => x.OperationId == operationId && x.HostId == hostId)
                ?? throw new InventoryFault(409, "OperationConflict");
            if (!item.Body.TryGetProperty("status", out var statusProperty)) throw new InventoryFault(400, "InvalidEvent");
            var body = ControlJson.Deserialize<NotificationOperationBody>("{\"status\":" + statusProperty.GetRawText() + "}");
            serverId = operation.ServerId;
            outcome = body.Status.ToString();
            kind = body.Status switch
            {
                OperationStatus.Succeeded when operation.Kind == OperationKind.CreateBackup => NotificationEventTypes.BackupSucceeded,
                OperationStatus.Failed when operation.Kind == OperationKind.CreateBackup => NotificationEventTypes.BackupFailed,
                OperationStatus.NeedsAttention => NotificationEventTypes.RecoveryRequired,
                OperationStatus.Succeeded or OperationStatus.Failed or OperationStatus.Rejected or OperationStatus.Expired or OperationStatus.Cancelled => NotificationEventTypes.Completed,
                _ => null
            };
        }
        else if (item.EventType == "RuntimeChanged")
        {
            var body = ControlJson.Deserialize<RuntimeChangedBody>(item.Body.GetRawText());
            serverId = body.Current.ServerId;
            if (serverId == Guid.Empty || body.Current.StateRevision < 1 || body.Previous is not null && (body.Previous.ServerId != serverId || body.Previous.StateRevision >= body.Current.StateRevision)) throw new InventoryFault(400, "InvalidEvent");
            if (!await db.Servers.AnyAsync(x => x.ServerId == serverId && x.HostId == hostId)) throw new InventoryFault(403, "HostScopeMismatch");
            kind = body.RecoveryRequired ? NotificationEventTypes.RecoveryRequired
                : body.UnexpectedExit ? NotificationEventTypes.Crashed
                : body.Current.ProcessState == ProcessState.Running && body.Previous?.ProcessState != ProcessState.Running ? NotificationEventTypes.Started
                : body.Current.ProcessState == ProcessState.Stopped && body.Previous?.ProcessState != ProcessState.Stopped ? NotificationEventTypes.Completed
                : null;
            outcome = body.Current.ProcessState.ToString();
        }
        else if (item.EventType == "ScheduleOccurrenceChanged")
        {
            var report = ControlJson.Deserialize<ScheduleOccurrenceReport>(item.Body.GetRawText());
            if (report.HostId != hostId || report.ScheduleId == Guid.Empty || report.OperationId == Guid.Empty || report.ServerId == Guid.Empty
                || report.Steps is null || report.Steps.Length > 32 || !Enum.IsDefined(report.Status)) throw new InventoryFault(400, "InvalidEvent");
            if (!await db.Servers.AnyAsync(x => x.ServerId == report.ServerId && x.HostId == hostId)) throw new InventoryFault(403, "HostScopeMismatch");
            kind = report.Status switch
            {
                ScheduleOccurrenceStatus.Running => NotificationEventTypes.ScheduleStarted,
                ScheduleOccurrenceStatus.Succeeded => NotificationEventTypes.ScheduleCompleted,
                ScheduleOccurrenceStatus.Failed => NotificationEventTypes.ScheduleFailed,
                ScheduleOccurrenceStatus.Skipped => NotificationEventTypes.ScheduleSkipped,
                ScheduleOccurrenceStatus.NeedsAttention => NotificationEventTypes.ScheduleRecoveryRequired,
                _ => null
            };
            scheduleId = report.ScheduleId; scheduleRunId = report.OperationId; serverId = report.ServerId;
            outcome = report.Status.ToString();
            scheduleStepsJson = ControlJson.Serialize(report.Steps.Select(x => new NotificationScheduleStep(x.StepKey, x.OperationId, x.Status.ToString())).ToArray());
            sourceKey = $"schedule:{hostId:D}:{report.OperationId:D}:{report.Status}";
            eventId = StableId(sourceKey);
            if (db.NotificationEvents.Local.Any(x => x.SourceKey == sourceKey) || await db.NotificationEvents.AnyAsync(x => x.SourceKey == sourceKey)) return;
        }
        if (kind is null) return;
        await StageCoreAsync(new NotificationEvent
        {
            EventId = eventId, SourceHostId = hostId, SourceEventSeq = item.EventSeq,
            SourceKey = sourceKey, ServerId = serverId, OperationId = item.OperationId,
            ScheduleId = scheduleId, ScheduleRunId = scheduleRunId, ScheduleStepsJson = scheduleStepsJson, EventType = kind,
            Outcome = outcome, OccurredAtUtc = item.OccurredAtUtc, RecordedAtUtc = clock.GetUtcNow(), RecordedAtUnixMs = clock.GetUtcNow().ToUnixTimeMilliseconds()
        });
    }

    // For Web-origin recovery and maintenance transitions, call within the owning
    // write transaction. A stable source key makes replay safe.
    public async Task StageWebEventAsync(string sourceKey, string eventType, DateTimeOffset occurredAtUtc, Guid? serverId = null, Guid? operationId = null, string? outcome = null)
    {
        if (sourceKey.Length is < 1 or > 256 || occurredAtUtc.Offset != TimeSpan.Zero || !AllowedType(eventType)) throw new ArgumentException("Invalid notification event.");
        var eventId = StableId("web:" + sourceKey);
        if (db.NotificationEvents.Local.Any(x => x.EventId == eventId) || await db.NotificationEvents.AnyAsync(x => x.EventId == eventId)) return;
        await StageCoreAsync(new NotificationEvent { EventId = eventId, EventType = eventType, ServerId = serverId, OperationId = operationId, Outcome = outcome, OccurredAtUtc = occurredAtUtc, RecordedAtUtc = clock.GetUtcNow(), RecordedAtUnixMs = clock.GetUtcNow().ToUnixTimeMilliseconds() });
    }

    private async Task StageCoreAsync(NotificationEvent row)
    {
        db.NotificationEvents.Add(row);
        var payload = ControlJson.Serialize(new NotificationPayload(row.EventId, row.EventType, row.OccurredAtUtc, row.SourceHostId, row.ServerId, row.OperationId, row.Outcome,
            row.ScheduleId, row.ScheduleRunId, row.ScheduleStepsJson is null ? null : ControlJson.Deserialize<NotificationScheduleStep[]>(row.ScheduleStepsJson)));
        foreach (var destination in await db.NotificationDestinations.AsNoTracking().Where(x => x.Enabled).ToArrayAsync())
            db.NotificationOutbox.Add(new NotificationDelivery { EventId = row.EventId, DestinationProfileId = destination.DestinationProfileId, PayloadJson = payload, NextAttemptAtUtc = clock.GetUtcNow(), NextAttemptUnixMs = clock.GetUtcNow().ToUnixTimeMilliseconds() });
    }

    public async Task<NotificationDestination[]> ListDestinationsAsync() => await db.NotificationDestinations.AsNoTracking().OrderBy(x => x.DisplayName).ToArrayAsync();

    public async Task<NotificationDestination> SaveDestinationAsync(Guid id, long expectedRevision, string displayName, string? url, bool enabled)
    {
        InventoryStore.ValidateDisplayName(displayName);
        if (id == Guid.Empty) throw new InventoryFault(400, "InvalidInput");
        return await store.WriteAsync(async () =>
        {
            var row = await db.NotificationDestinations.SingleOrDefaultAsync(x => x.DestinationProfileId == id);
            if (row is null)
            {
                if (expectedRevision != 0 || string.IsNullOrWhiteSpace(url)) throw new InventoryFault(409, "StaleRevision");
                row = new NotificationDestination { DestinationProfileId = id, CreatedAtUtc = clock.GetUtcNow() };
                db.NotificationDestinations.Add(row);
            }
            else
            {
                if (expectedRevision == 0)
                {
                    if (row.Revision == 1 && row.DisplayName == displayName && row.Enabled == enabled
                        && url is not null && UnprotectUrl(row) == NotificationHttpHandler.ValidateUrl(url).AbsoluteUri) return row;
                    throw new InventoryFault(409, "OperationConflict");
                }
                if (row.Revision != expectedRevision) throw new InventoryFault(409, "StaleRevision");
                row.Revision++;
            }
            row.DisplayName = displayName;
            if (!string.IsNullOrWhiteSpace(url)) row.ProtectedUrl = protector.Protect(NotificationHttpHandler.ValidateUrl(url).AbsoluteUri);
            row.Enabled = enabled;
            row.UpdatedAtUtc = clock.GetUtcNow();
            return row;
        });
    }

    internal string UnprotectUrl(NotificationDestination destination) => protector.Unprotect(destination.ProtectedUrl);

    private static bool AllowedType(string type) => type is NotificationEventTypes.Started or NotificationEventTypes.Completed or NotificationEventTypes.Crashed or NotificationEventTypes.BackupSucceeded or NotificationEventTypes.BackupFailed or NotificationEventTypes.RecoveryRequired;
    private static Guid StableId(string source)
    {
        // UUIDv5 keeps the same eventId across Agent replay and Web restart.
        var namespaceBytes = Guid.Parse("bf596e6e-3673-4eb3-9adb-46d4e86e4497").ToByteArray(bigEndian: true);
        var nameBytes = Encoding.UTF8.GetBytes(source);
        var input = new byte[namespaceBytes.Length + nameBytes.Length];
        namespaceBytes.CopyTo(input, 0);
        nameBytes.CopyTo(input, namespaceBytes.Length);
        var id = SHA1.HashData(input)[..16];
        id[6] = (byte)((id[6] & 0x0f) | 0x50);
        id[8] = (byte)((id[8] & 0x3f) | 0x80);
        return Guid.ParseExact(Convert.ToHexString(id), "N");
    }
    private sealed record NotificationOperationBody(OperationStatus Status);
}
