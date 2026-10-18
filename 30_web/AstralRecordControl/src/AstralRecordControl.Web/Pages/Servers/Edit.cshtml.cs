using System.Text.Json;
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
public sealed class EditModel(ControlDbContext db, InventoryStore store, InventoryNotifier notifier) : PageModel
{
    public InventoryServer Server { get; private set; } = null!;
    public ServerConfig Config { get; private set; } = null!;
    public HostResources Resources { get; private set; } = new();
    public InventoryEnvironment[] Environments { get; private set; } = [];
    public string? Error { get; private set; }
    public async Task<IActionResult> OnGetAsync(Guid serverId) => await LoadAsync(serverId) ? Page() : NotFound();
    public async Task<IActionResult> OnPostAsync(Guid serverId, long revision, string displayName, Guid? environmentId, string? runtimeServerId, Guid managedRootId, Guid executableId, string serverDirectory, string? arguments, string stopCommand, int? listenPort)
    {
        if (!await LoadAsync(serverId)) return NotFound();
        try
        {
            if (!ModelState.IsValid) throw new InventoryFault(400, "InvalidInput");
            var config = Config with { Revision = checked(revision + 1), ManagedRootId = managedRootId, ExecutableId = executableId, ServerDirectory = serverDirectory, ArgumentList = string.IsNullOrEmpty(arguments) ? [] : arguments.Replace("\r\n", "\n").Split('\n'), StopCommand = stopCommand, ListenPort = listenPort };
            var row = await store.UpdateServerAsync(serverId, new(displayName, environmentId, runtimeServerId, config), revision, ControlJson.Serialize(config));
            await notifier.InventoryChangedAsync(row.HostId); return RedirectToPage(new { serverId });
        }
        catch (Exception exception) when (exception is InventoryFault or ArgumentException or JsonException or OverflowException) { Error = exception.Message; await LoadAsync(serverId); return Page(); }
    }
    private async Task<bool> LoadAsync(Guid serverId)
    {
        var row = await db.Servers.AsNoTracking().SingleOrDefaultAsync(x => x.ServerId == serverId);
        if (row == null) return false;
        Server = row; Config = ControlJson.Deserialize<ServerConfig>(row.DesiredConfigJson);
        Resources = ControlJson.Deserialize<HostResources>((await store.HostAsync(row.HostId)).ResourcesJson);
        Environments = await db.Environments.AsNoTracking().OrderBy(x => x.EnvironmentKey).ToArrayAsync(); return true;
    }
}
