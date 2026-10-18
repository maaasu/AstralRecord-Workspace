using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Web.Pages;

[Authorize(Policy = "OwnerOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class IndexModel(ControlDbContext db, TimeProvider clock) : PageModel
{
    public InventoryHost[] Hosts { get; private set; } = [];
    public InventoryServer[] Servers { get; private set; } = [];
    public DateTimeOffset ObservedAtUtc { get; private set; }
    public async Task OnGetAsync()
    {
        ObservedAtUtc = clock.GetUtcNow();
        Hosts = await db.Hosts.AsNoTracking().OrderBy(x => x.HostKey).ToArrayAsync();
        foreach (var host in Hosts)
            if (host.Enabled && host.ConnectionState is HostConnectionState.Online or HostConnectionState.Reconciling && (host.LastSeenAtUtc == null || ObservedAtUtc - host.LastSeenAtUtc > TimeSpan.FromSeconds(30))) host.ConnectionState = HostConnectionState.Offline;
        Servers = await db.Servers.AsNoTracking().OrderBy(x => x.ServerKey).ToArrayAsync();
    }
}
