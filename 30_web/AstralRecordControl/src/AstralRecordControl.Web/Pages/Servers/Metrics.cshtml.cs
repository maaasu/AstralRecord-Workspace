using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Metrics;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Pages.Servers;

[Authorize(Policy = "OwnerOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class MetricsModel(ControlDbContext db) : PageModel
{
    public InventoryServer Server { get; private set; } = null!;
    public string ConnectionState { get; private set; } = "Offline";
    public string? ProcessState { get; private set; }
    public async Task<IActionResult> OnGetAsync(Guid serverId)
    {
        Server = await db.Servers.AsNoTracking().SingleOrDefaultAsync(s => s.ServerId == serverId)
            ?? throw new InventoryFault(404, "NotFound");
        var host = await db.Hosts.AsNoTracking().SingleAsync(h => h.HostId == Server.HostId);
        ConnectionState = host.ConnectionState.ToString();
        if (host.ConnectionState == Contracts.HostConnectionState.Online)
            ProcessState = Contracts.ControlJson.Deserialize<Contracts.ServerRuntimeSnapshot[]>(host.RuntimeSnapshotJson)
                .SingleOrDefault(s => s.ServerId == serverId)?.ProcessState.ToString();
        return Page();
    }
}
