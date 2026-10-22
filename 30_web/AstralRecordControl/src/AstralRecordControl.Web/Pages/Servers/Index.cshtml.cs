using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Hubs;
using AstralRecordControl.Web.Inventory;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Pages.Servers;

[Authorize(Policy = "OwnerOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class IndexModel(ControlDbContext db, InventoryStore store, InventoryNotifier notifier) : PageModel
{
    public InventoryHost[] Hosts { get; private set; } = [];
    public InventoryServer[] Servers { get; private set; } = [];
    public InventoryEnvironment[] Environments { get; private set; } = [];
    public string? Error { get; private set; }
    public Guid RequestKey { get; private set; } = Guid.NewGuid();
    public Guid ServerId { get; private set; } = Guid.NewGuid();
    public async Task OnGetAsync() => await LoadAsync();
    public async Task<IActionResult> OnPostCreateAsync(Guid requestKey, Guid serverId, Guid hostId, string serverKey, string displayName, Guid? environmentId, string? runtimeServerId, ServerKind kind, Guid managedRootId, Guid executableId, string serverDirectory, string? arguments, int? listenPort, string? stopCommand)
    {
        try
        {
            if (!ModelState.IsValid) throw new InventoryFault(400, "InvalidInput");
            var config = new ServerConfig { ServerId = serverId, HostId = hostId, Revision = 1, Kind = kind, ManagedRootId = managedRootId, ExecutableId = executableId, ServerDirectory = serverDirectory, ArgumentList = string.IsNullOrEmpty(arguments) ? [] : arguments.Replace("\r\n", "\n").Split('\n'), ListenPort = listenPort, StopCommand = string.IsNullOrEmpty(stopCommand) ? ServerProcessSupport.DefaultStopCommand(kind) : stopCommand };
            var body = new ServerCreateRequest(hostId, serverKey, displayName, environmentId, runtimeServerId, config);
            var row = await store.CreateServerAsync(body, requestKey, ControlJson.Serialize(body), ControlJson.Serialize(config));
            await notifier.InventoryChangedAsync(row.HostId); return RedirectToPage();
        }
        catch (Exception exception) when (exception is InventoryFault or ArgumentException or NullReferenceException) { Error = exception.Message; await LoadAsync(); return Page(); }
    }
    private async Task LoadAsync()
    {
        Hosts = await db.Hosts.AsNoTracking().OrderBy(x => x.HostKey).ToArrayAsync();
        Servers = await db.Servers.AsNoTracking().OrderBy(x => x.ServerKey).ToArrayAsync();
        Environments = await db.Environments.AsNoTracking().OrderBy(x => x.EnvironmentKey).ToArrayAsync();
    }
    public HostResources Resources(InventoryHost host) => ControlJson.Deserialize<HostResources>(host.ResourcesJson);
    public ServerConfig Configuration(InventoryServer server) => ControlJson.Deserialize<ServerConfig>(server.DesiredConfigJson);
}
