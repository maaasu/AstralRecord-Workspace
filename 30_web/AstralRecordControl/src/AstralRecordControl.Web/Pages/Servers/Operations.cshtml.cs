using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Operations;
using AstralRecordControl.Web.Pages.Shared;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Pages.Servers;

[Authorize(Policy = "OwnerOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class OperationsModel(ControlDbContext db, ControlOperationsService operations, ReservationService reservations,
    IConfiguration configuration, TimeProvider clock, InventoryStore? store = null) : PageModel
{
    public InventoryServer Server { get; private set; } = null!;
    public ServerKind Kind { get; private set; }
    public ProcessControlAvailability Availability { get; private set; } = null!;
    public ServerRuntimeSnapshot? Runtime => Availability.Runtime;
    public ControlOperation[] Recent { get; private set; } = [];
    public GateLease[] MaintenanceLeases { get; private set; } = [];
    public LogBatch? Logs { get; private set; }
    public bool Enabled => Availability.Enabled;
    public bool OperationPending => Availability.OperationPending;
    public string? Error { get; private set; }

    public async Task<IActionResult> OnGetAsync(Guid serverId, long? afterSequence)
    {
        if (!await LoadAsync(serverId)) return NotFound();
        await ReadInitialLogsAsync(afterSequence ?? 0);
        return Page();
    }
    public async Task<IActionResult> OnGetStatusAsync(Guid serverId)
    {
        var server = await db.Servers.AsNoTracking().SingleOrDefaultAsync(x => x.ServerId == serverId);
        if (server is null) return NotFound();
        var host = await db.Hosts.AsNoTracking().SingleOrDefaultAsync(x => x.HostId == server.HostId);
        var config = ControlJson.Deserialize<ServerConfig>(server.DesiredConfigJson);
        var (availability, maintenanceLeases) = await LoadAvailabilityAsync(server, host, config);
        var recent = (await ReadRecentOperationsAsync(serverId))
            .Select(x => new { createdAtUtc = x.CreatedAtUtc, kind = x.Kind.ToString(), status = x.Status.ToString(),
                errorCode = x.ErrorCode, errorMessage = ControlDisplay.OperationError(x.ErrorCode), workflowId = x.WorkflowId }).ToArray();
        return new JsonResult(new
        {
            enabled = availability.Enabled, processState = availability.Runtime?.ProcessState.ToString(), stateRevision = availability.Runtime?.StateRevision,
            currentSessionId = availability.Runtime?.CurrentSessionId, configRevision = server.AppliedRevision,
            operationPending = availability.OperationPending, canStart = availability.CanStart, canStop = availability.CanStop,
            canRun = availability.CanRun, reason = availability.Reason, workflowId = availability.WorkflowId,
            maintenanceStarts = maintenanceLeases.Select(x => new { reservationId = x.ReservationId, workflowId = x.WorkflowId, adapterId = x.AdapterId,
                usesAppliedSettings = ProcessControlAvailability.HasPendingAppliedSettings(server) }).ToArray(),
            recent
        });
    }
    public async Task<IActionResult> OnPostOperateAsync(Guid serverId, Guid requestKey, OperationKind? kind, string? command, string? reason, Guid? reservationId, long configRevision, long expectedStateRevision, Guid? expectedServerSessionId, bool confirmKill = false)
    {
        try
        {
            if (kind is not (OperationKind.Start or OperationKind.Stop or OperationKind.Restart or OperationKind.Kill or OperationKind.ConsoleCommand))
                throw new InventoryFault(400, "操作種別を確認できません。画面を再読み込みして操作してください。");
            if (!ModelState.IsValid || requestKey == Guid.Empty) throw new InventoryFault(400, "操作を確認できません。画面を再読み込みしてください。");
            if (kind == OperationKind.Kill && !confirmKill) throw new InventoryFault(400, "対象セッションと強制終了の確認が必要です。");
            if (!await LoadAsync(serverId)) return NotFound();
            // Replays still reach the service's request-hash check after availability has changed.
            var replay = await db.Operations.AsNoTracking().AnyAsync(x => x.IdempotencyKey == requestKey);
            var payload = kind switch
            {
                OperationKind.ConsoleCommand => ControlJson.Element(new { command }),
                OperationKind.Kill => ControlJson.Element(new { reason }),
                _ => ControlJson.Element(new { })
            };
            GateLease? lease = null;
            if (reservationId is Guid id)
            {
                if (kind != OperationKind.Start) throw new InventoryFault(409, "ReservationConflict");
                lease = replay ? await reservations.GetByReservationAsync(id) : MaintenanceLeases.SingleOrDefault(x => x.ReservationId == id);
                if (lease is null) throw new InventoryFault(409, "ReservationConflict");
            }
            else if (!replay)
            {
                var allowed = kind switch
                {
                    OperationKind.Start => Availability.CanStart,
                    OperationKind.Stop => Availability.CanStop,
                    _ => Availability.CanRun
                };
                if (!allowed) throw new InventoryFault(409, Availability.Reason ?? "サーバーの稼働状態が変わりました。最新の状態を確認してください。");
            }
            var request = new ServerOperationRequest { Kind = kind.Value, Payload = payload, ConfigRevision = configRevision, ExpectedStateRevision = expectedStateRevision, ExpectedServerSessionId = expectedServerSessionId, WorkflowId = lease?.WorkflowId, ReservationId = lease?.ReservationId };
            await operations.CreateAsync(serverId, request, requestKey, HttpContext.RequestAborted);
            return RedirectToPage(new { serverId });
        }
        catch (Exception exception) when (exception is InventoryFault or ArgumentException)
        {
            Error = exception is InventoryFault fault ? ControlDisplay.OperationError(fault.Code) : exception.Message;
            if (!await LoadAsync(serverId)) return NotFound();
            await ReadInitialLogsAsync(0);
            return Page();
        }
    }
    private async Task<bool> LoadAsync(Guid serverId)
    {
        var server = await db.Servers.AsNoTracking().SingleOrDefaultAsync(x => x.ServerId == serverId);
        if (server is null) return false;
        Server = server;
        var host = await db.Hosts.AsNoTracking().SingleOrDefaultAsync(x => x.HostId == Server.HostId);
        var config = ControlJson.Deserialize<ServerConfig>(Server.DesiredConfigJson);
        Kind = config.Kind;
        (Availability, MaintenanceLeases) = await LoadAvailabilityAsync(Server, host, config);
        Recent = await ReadRecentOperationsAsync(serverId);
        return true;
    }

    private async Task<(ProcessControlAvailability Availability, GateLease[] MaintenanceLeases)> LoadAvailabilityAsync(
        InventoryServer server, InventoryHost? host, ServerConfig config)
    {
        var pending = await ProcessControlAvailability.PendingOperations(db).Where(x => x.ServerId == server.ServerId)
            .OrderByDescending(x => x.Status == OperationStatus.NeedsAttention).FirstOrDefaultAsync();
        var active = await ProcessControlAvailability.ActiveReservations(db).ToArrayAsync();
        var now = clock.GetUtcNow();
        var runtime = ProcessControlAvailability.HasCurrentReport(host, now)
            ? ControlJson.Deserialize<ServerRuntimeSnapshot[]>(host!.RuntimeSnapshotJson).SingleOrDefault(x => x.ServerId == server.ServerId) : null;
        var applied = store is not null && host is not null && runtime?.ProcessState is ProcessState.Running or ProcessState.Stopped
            && ProcessControlAvailability.HasPendingAppliedSettings(server) ? await store.AppliedServerConfigAsync(server, host) : null;
        var conflicts = ProcessControlAvailability.ConflictingReservations(server, config, active,
            runtime?.ProcessState == ProcessState.Running ? applied : null).ToArray();
        var availability = ProcessControlAvailability.For(server, config, host, runtime, now,
            configuration.GetValue<bool>("Control:EnableProcessControl"), pending, conflicts.FirstOrDefault(), applied);
        if ((!availability.Enabled && !availability.AppliedSettingsAvailable) || !availability.CanStartWithinReservation) return (availability, []);
        var serverKey = ReservationService.ServerKey(server.ServerId);
        var held = conflicts.SingleOrDefault(x => x.ResourceKey == serverKey && x.State == ReservationStatus.Prepared);
        if (held is null || (availability.Enabled && conflicts.Any(x => x.ReservationId != held.ReservationId))) return (availability, []);
        var waiting = await (from lease in db.ReservationLeases.AsNoTracking()
                             join workflow in db.Workflows.AsNoTracking() on lease.WorkflowId equals workflow.WorkflowId
                             where lease.ReservationId == held.ReservationId && lease.WorkflowId == held.OwnerWorkflowId
                                 && lease.Status == ReservationStatus.Prepared && lease.LastStepKey == "WaitForStartup"
                                 && (lease.AdapterId == "01" || lease.AdapterId == "03" || lease.AdapterId == "16")
                                 && workflow.Status == WorkflowStatus.WaitingForStartup
                             select workflow.RequestJson).SingleOrDefaultAsync();
        if (waiting is null) return (availability, []);
        var request = ControlJson.Deserialize<ReservationAcquireRequest>(waiting);
        if ((request.DevLifecycleServerId is Guid target && (!availability.Enabled || target != server.ServerId))
            || !request.ServerConditions.Any(x => x.ServerId == server.ServerId && x.RequiredState == ProcessRequirement.Stopped))
            return (availability, []);
        var candidate = await reservations.GetByReservationAsync(held.ReservationId);
        if (candidate.Status != ReservationStatus.Prepared || !candidate.Fences.Any(x => x.HostId == server.HostId)
            || (!availability.Enabled && !ControlOperationsService.CoversAppliedServer(candidate, server, applied!))) return (availability, []);
        return (availability, [candidate]);
    }

    private async Task ReadInitialLogsAsync(long afterSequence)
    {
        if (!ServerProcessSupport.IsSupported(Kind)) return;
        try { Logs = await operations.ReadLogsAsync(Server.ServerId, new ReadLogsRequest { ServerId = Server.ServerId, AfterSequence = afterSequence, Limit = 200 }, HttpContext.RequestAborted); }
        catch (Exception exception) when (exception is InventoryFault or IOException or OperationCanceledException or HubException) { }
    }

    private async Task<ControlOperation[]> ReadRecentOperationsAsync(Guid serverId)
    {
        // Operations are written as UTC. SQLite can order their stored TEXT values without EF's DateTimeOffset translation.
        var recent = await db.Operations
            .FromSqlInterpolated($"SELECT o.* FROM \"Operations\" AS o JOIN \"Workflows\" AS w ON w.\"WorkflowId\" = o.\"WorkflowId\" WHERE o.\"ServerId\" = {serverId} AND w.\"HiddenAtUtc\" IS NULL ORDER BY o.\"CreatedAtUtc\" DESC, o.\"OperationId\" DESC LIMIT 20")
            .AsNoTracking()
            .Select(x => new ControlOperation { OperationId = x.OperationId, WorkflowId = x.WorkflowId, CreatedAtUtc = x.CreatedAtUtc, Kind = x.Kind, Status = x.Status, ErrorCode = x.ErrorCode })
            .ToArrayAsync();
        return recent.OrderByDescending(x => x.CreatedAtUtc).ThenByDescending(x => x.OperationId).ToArray();
    }
}
