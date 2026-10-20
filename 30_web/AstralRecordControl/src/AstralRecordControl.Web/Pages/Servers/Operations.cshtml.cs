using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Operations;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Pages.Servers;

[Authorize(Policy = "OwnerOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class OperationsModel(ControlDbContext db, ControlOperationsService operations, ReservationService reservations, IConfiguration configuration, TimeProvider clock) : PageModel
{
    public InventoryServer Server { get; private set; } = null!;
    public ServerRuntimeSnapshot? Runtime { get; private set; }
    public ControlOperation[] Recent { get; private set; } = [];
    public GateLease[] MaintenanceLeases { get; private set; } = [];
    public LogBatch? Logs { get; private set; }
    public bool Enabled { get; private set; }
    public bool OperationPending { get; private set; }
    public string? Error { get; private set; }

    public async Task<IActionResult> OnGetAsync(Guid serverId, long? afterSequence)
    {
        await LoadAsync(serverId, afterSequence ?? 0);
        return Page();
    }
    public async Task<IActionResult> OnGetStatusAsync(Guid serverId)
    {
        var server = await db.Servers.AsNoTracking().SingleOrDefaultAsync(x => x.ServerId == serverId);
        if (server is null) return NotFound();
        var host = await db.Hosts.AsNoTracking().SingleOrDefaultAsync(x => x.HostId == server.HostId);
        if (host is null) return NotFound();
        var hostFresh = host.ConnectionState == HostConnectionState.Online && host.LastSeenAtUtc.HasValue
            && clock.GetUtcNow() - host.LastSeenAtUtc.Value <= TimeSpan.FromSeconds(30);
        var runtime = hostFresh ? ControlJson.Deserialize<ServerRuntimeSnapshot[]>(host.RuntimeSnapshotJson).SingleOrDefault(x => x.ServerId == serverId) : null;
        var capabilities = ControlJson.Deserialize<string[]>(host.CapabilitiesJson);
        var config = ControlJson.Deserialize<ServerConfig>(server.DesiredConfigJson);
        var enabled = configuration.GetValue<bool>("Control:EnableProcessControl") && host.Enabled && !host.RecoveryRequired
            && hostFresh
            && capabilities.Contains("P1.Operations") && capabilities.Contains("P1.LegacyGate")
            && config.Kind == ServerKind.Java && config.DependencyReadiness != DependencyReadiness.AstralReady
            && runtime is not null && server.AppliedRevision == server.DesiredRevision && runtime.ConfigRevision == server.AppliedRevision;
        var recent = (await ReadRecentOperationsAsync(serverId))
            .Select(x => new { createdAtUtc = x.CreatedAtUtc, kind = x.Kind.ToString(), status = x.Status.ToString(), errorCode = x.ErrorCode }).ToArray();
        return new JsonResult(new
        {
            enabled, processState = runtime?.ProcessState.ToString(), stateRevision = runtime?.StateRevision,
            currentSessionId = runtime?.CurrentSessionId, configRevision = server.AppliedRevision,
            operationPending = await HasPendingOperationAsync(serverId),
            recent
        });
    }
    public async Task<IActionResult> OnPostOperateAsync(Guid serverId, Guid requestKey, OperationKind? kind, string? command, string? reason, Guid? reservationId, long configRevision, long expectedStateRevision, Guid? expectedServerSessionId, bool confirmKill = false)
    {
        try
        {
            if (!kind.HasValue || !Enum.IsDefined(kind.Value)) throw new InventoryFault(400, "操作種別を確認できません。画面を再読み込みして操作してください。");
            await LoadAsync(serverId, 0);
            if (!Enabled || Runtime is null) throw new InventoryFault(409, "UnsupportedCapability");
            if (!ModelState.IsValid || (kind == OperationKind.Kill && !confirmKill)) throw new InventoryFault(400, "Killには対象Sessionと強制終了の確認が必要です。");
            var payload = kind switch
            {
                OperationKind.ConsoleCommand => ControlJson.Element(new { command }),
                OperationKind.Kill => ControlJson.Element(new { reason }),
                _ => ControlJson.Element(new { })
            };
            var lease = reservationId.HasValue ? MaintenanceLeases.SingleOrDefault(x => x.ReservationId == reservationId.Value) ?? throw new InventoryFault(409, "ReservationConflict") : null;
            if (lease != null && kind != OperationKind.Start) throw new InventoryFault(409, "ReservationConflict");
            var request = new ServerOperationRequest { Kind = kind.Value, Payload = payload, ConfigRevision = configRevision, ExpectedStateRevision = expectedStateRevision, ExpectedServerSessionId = expectedServerSessionId, WorkflowId = lease?.WorkflowId, ReservationId = lease?.ReservationId };
            await operations.CreateAsync(serverId, request, requestKey, HttpContext.RequestAborted);
            return RedirectToPage(new { serverId });
        }
        catch (Exception exception) when (exception is InventoryFault or ArgumentException)
        {
            Error = exception.Message;
            if (Server is null) await LoadAsync(serverId, 0);
            return Page();
        }
    }
    private async Task LoadAsync(Guid serverId, long afterSequence)
    {
        Server = await db.Servers.AsNoTracking().SingleOrDefaultAsync(x => x.ServerId == serverId) ?? throw new InventoryFault(404, "NotFound");
        var host = await db.Hosts.AsNoTracking().SingleAsync(x => x.HostId == Server.HostId);
        var hostFresh = host.ConnectionState == HostConnectionState.Online && host.LastSeenAtUtc.HasValue
            && clock.GetUtcNow() - host.LastSeenAtUtc.Value <= TimeSpan.FromSeconds(30);
        Runtime = hostFresh ? ControlJson.Deserialize<ServerRuntimeSnapshot[]>(host.RuntimeSnapshotJson).SingleOrDefault(x => x.ServerId == serverId) : null;
        var capabilities = ControlJson.Deserialize<string[]>(host.CapabilitiesJson);
        var config = ControlJson.Deserialize<ServerConfig>(Server.DesiredConfigJson);
        Enabled = configuration.GetValue<bool>("Control:EnableProcessControl") && host.Enabled && !host.RecoveryRequired
            && hostFresh
            && capabilities.Contains("P1.Operations") && capabilities.Contains("P1.LegacyGate")
            && config.Kind == ServerKind.Java && config.DependencyReadiness != DependencyReadiness.AstralReady
            && Runtime != null && Runtime.ConfigRevision == Server.AppliedRevision && Server.AppliedRevision == Server.DesiredRevision;
        Recent = await ReadRecentOperationsAsync(serverId);
        OperationPending = await HasPendingOperationAsync(serverId);
        var active = await db.Reservations.AsNoTracking().Where(x => x.ResourceKey == ReservationService.ServerKey(serverId) && x.State == ReservationStatus.Prepared).Select(x => x.ReservationId).ToArrayAsync();
        var waiting = (await db.Workflows.AsNoTracking().Where(x => x.Status == WorkflowStatus.WaitingForStartup).ToArrayAsync())
            .Where(x => ControlJson.Deserialize<ReservationAcquireRequest>(x.RequestJson).ServerConditions.Any(c => c.ServerId == serverId && c.RequiredState == ProcessRequirement.Stopped))
            .Select(x => x.WorkflowId).ToArray();
        var ready = await db.ReservationLeases.AsNoTracking().Where(x => x.LastStepKey == "WaitForStartup").Select(x => x.ReservationId).ToArrayAsync();
        MaintenanceLeases = (await Task.WhenAll(active.Select(id => reservations.GetByReservationAsync(id)))).Where(x => x.AdapterId is "01" or "03" or "16" && waiting.Contains(x.WorkflowId) && ready.Contains(x.ReservationId)).ToArray();
        if (config.Kind == ServerKind.Java)
        {
            try { Logs = await operations.ReadLogsAsync(serverId, new ReadLogsRequest { ServerId = serverId, AfterSequence = afterSequence, Limit = 200 }, HttpContext.RequestAborted); }
            catch (Exception exception) when (exception is InventoryFault or IOException or OperationCanceledException) { }
        }
    }

    private async Task<ControlOperation[]> ReadRecentOperationsAsync(Guid serverId)
    {
        // Operations are written as UTC. SQLite can order their stored TEXT values without EF's DateTimeOffset translation.
        var recent = await db.Operations
            .FromSqlInterpolated($"SELECT * FROM \"Operations\" WHERE \"ServerId\" = {serverId} ORDER BY \"CreatedAtUtc\" DESC, \"OperationId\" DESC LIMIT 20")
            .AsNoTracking()
            .Select(x => new ControlOperation { OperationId = x.OperationId, CreatedAtUtc = x.CreatedAtUtc, Kind = x.Kind, Status = x.Status, ErrorCode = x.ErrorCode })
            .ToArrayAsync();
        return recent.OrderByDescending(x => x.CreatedAtUtc).ThenByDescending(x => x.OperationId).ToArray();
    }

    private Task<bool> HasPendingOperationAsync(Guid serverId) => db.Operations.AsNoTracking().AnyAsync(x => x.ServerId == serverId
        && (x.Status == OperationStatus.Queued || x.Status == OperationStatus.Accepted || x.Status == OperationStatus.Running || x.Status == OperationStatus.NeedsAttention));
}
