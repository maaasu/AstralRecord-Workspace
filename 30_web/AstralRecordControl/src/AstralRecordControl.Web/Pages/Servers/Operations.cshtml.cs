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
public sealed class OperationsModel(ControlDbContext db, ControlOperationsService operations, ReservationService reservations, IConfiguration configuration) : PageModel
{
    public InventoryServer Server { get; private set; } = null!;
    public ServerRuntimeSnapshot? Runtime { get; private set; }
    public ControlOperation[] Recent { get; private set; } = [];
    public GateLease[] MaintenanceLeases { get; private set; } = [];
    public LogBatch? Logs { get; private set; }
    public bool Enabled { get; private set; }
    public string? Error { get; private set; }
    public Guid RequestKey { get; private set; } = Guid.NewGuid();

    public async Task<IActionResult> OnGetAsync(Guid serverId, long? afterSequence)
    {
        await LoadAsync(serverId, afterSequence ?? 0);
        return Page();
    }
    public async Task<IActionResult> OnPostOperateAsync(Guid serverId, Guid requestKey, OperationKind kind, string? command, string? reason, Guid? reservationId, long configRevision, long expectedStateRevision, Guid? expectedServerSessionId)
    {
        try
        {
            await LoadAsync(serverId, 0);
            if (!Enabled || Runtime is null) throw new InventoryFault(409, "UnsupportedCapability");
            var payload = kind switch
            {
                OperationKind.ConsoleCommand => ControlJson.Element(new { command }),
                OperationKind.Kill => ControlJson.Element(new { reason }),
                _ => ControlJson.Element(new { })
            };
            var lease = reservationId.HasValue ? MaintenanceLeases.SingleOrDefault(x => x.ReservationId == reservationId.Value) ?? throw new InventoryFault(409, "ReservationConflict") : null;
            var request = new ServerOperationRequest { Kind = kind, Payload = payload, ConfigRevision = configRevision, ExpectedStateRevision = expectedStateRevision, ExpectedServerSessionId = expectedServerSessionId, WorkflowId = lease?.WorkflowId, ReservationId = lease?.ReservationId };
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
        Runtime = ControlJson.Deserialize<ServerRuntimeSnapshot[]>(host.RuntimeSnapshotJson).SingleOrDefault(x => x.ServerId == serverId);
        var capabilities = ControlJson.Deserialize<string[]>(host.CapabilitiesJson);
        Enabled = configuration.GetValue<bool>("Control:EnableProcessControl") && host.ConnectionState == HostConnectionState.Online && capabilities.Contains("P1.Operations") && capabilities.Contains("P1.LegacyGate") && Runtime != null && Server.AppliedRevision == Server.DesiredRevision;
        Recent = await db.Operations.AsNoTracking().Where(x => x.ServerId == serverId).OrderByDescending(x => x.CreatedAtUtc).Take(20).ToArrayAsync();
        var active = await db.Reservations.AsNoTracking().Where(x => x.ResourceKey == ReservationService.ServerKey(serverId) && x.State == ReservationStatus.Prepared).Select(x => x.ReservationId).ToArrayAsync();
        MaintenanceLeases = (await Task.WhenAll(active.Select(id => reservations.GetByReservationAsync(id)))).Where(x => x.AdapterId != null).ToArray();
        try { Logs = await operations.ReadLogsAsync(serverId, new ReadLogsRequest { ServerId = serverId, AfterSequence = afterSequence, Limit = 200 }, HttpContext.RequestAborted); }
        catch (Exception exception) when (exception is InventoryFault or IOException or OperationCanceledException) { }
    }
}
