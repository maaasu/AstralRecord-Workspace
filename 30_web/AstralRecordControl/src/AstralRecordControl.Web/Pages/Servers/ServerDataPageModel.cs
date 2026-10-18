using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Pages.Servers;

public abstract class ServerDataPageModel(ControlDbContext db, IConfiguration configuration) : PageModel
{
    public InventoryServer Server { get; private set; } = null!;
    public ServerConfig Config { get; private set; } = null!;
    public HostResources Resources { get; private set; } = null!;
    public bool Enabled { get; private set; }
    public async Task<IActionResult> OnGetAsync(Guid serverId)
    {
        Server = await db.Servers.AsNoTracking().SingleOrDefaultAsync(s => s.ServerId == serverId) ?? throw new InventoryFault(404, "NotFound");
        Config = ControlJson.Deserialize<ServerConfig>(Server.DesiredConfigJson);
        var host = await db.Hosts.AsNoTracking().SingleAsync(h => h.HostId == Server.HostId);
        Resources = ControlJson.Deserialize<HostResources>(host.ResourcesJson);
        var capabilities = ControlJson.Deserialize<string[]>(host.CapabilitiesJson);
        Enabled = configuration.GetValue<bool>("Control:EnableProcessControl") && configuration.GetValue<bool>("Control:EnableFileControl") && capabilities.Contains("P2.Files") && capabilities.Contains("P2.Backups") && host.ConnectionState == HostConnectionState.Online;
        return Page();
    }
}
