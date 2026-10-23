using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Hubs;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Pages.Shared;
using AstralRecordControl.Web.Operations;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Pages.Servers;

[Authorize(Policy = "OwnerOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class IndexModel(ControlDbContext db, InventoryStore store, InventoryNotifier notifier, TimeProvider clock, IConfiguration configuration, ControlOperationsService operations) : PageModel
{
    private Dictionary<Guid, ServerRuntimeSnapshot> runtime = [];
    private HashSet<Guid> pendingServers = [];
    private HashSet<string> reservedResources = [];
    public DateTimeOffset ObservedAtUtc { get; private set; }
    public InventoryHost[] Hosts { get; private set; } = [];
    public InventoryServer[] Servers { get; private set; } = [];
    public InventoryEnvironment[] Environments { get; private set; } = [];
    public string? Error { get; private set; }
    public Guid RequestKey { get; private set; } = Guid.NewGuid();
    public Guid ServerId { get; private set; } = Guid.NewGuid();
    public async Task OnGetAsync() => await LoadAsync();
    public async Task<IActionResult> OnPostOperateAsync(Guid serverId, Guid requestKey, OperationKind? kind, long configRevision, long expectedStateRevision, Guid? expectedServerSessionId)
    {
        try
        {
            if (!ModelState.IsValid || requestKey == Guid.Empty || kind is not (OperationKind.Start or OperationKind.Stop))
                throw new InventoryFault(400, "起動または正常停止を選択してください。");
            await LoadAsync();
            var server = Servers.SingleOrDefault(x => x.ServerId == serverId) ?? throw new InventoryFault(404, "NotFound");
            // Replays must reach the service's request-hash check even after state or reservations change.
            if (!await db.Operations.AsNoTracking().AnyAsync(x => x.IdempotencyKey == requestKey))
            {
                var actions = Actions(server);
                if (kind == OperationKind.Start ? !actions.CanStart : !actions.CanStop)
                    throw new InventoryFault(409, "現在の状態では操作できません。PC接続・設定適用・処理中の操作や保守を確認してください。");
            }
            await operations.CreateAsync(serverId, new ServerOperationRequest
            {
                Kind = kind.Value, Payload = ControlJson.Element(new { }), ConfigRevision = configRevision,
                ExpectedStateRevision = expectedStateRevision, ExpectedServerSessionId = expectedServerSessionId
            }, requestKey, HttpContext.RequestAborted);
            return RedirectToPage();
        }
        catch (Exception exception) when (exception is InventoryFault or ArgumentException)
        {
            Error = exception.Message;
            await LoadAsync();
            return Page();
        }
    }
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
        ObservedAtUtc = clock.GetUtcNow();
        Hosts = await db.Hosts.AsNoTracking().OrderBy(x => x.HostKey).ToArrayAsync();
        runtime = Hosts.SelectMany(h => ControlJson.Deserialize<ServerRuntimeSnapshot[]>(h.RuntimeSnapshotJson)).ToDictionary(x => x.ServerId);
        Servers = await db.Servers.AsNoTracking().OrderBy(x => x.ServerKey).ToArrayAsync();
        Environments = await db.Environments.AsNoTracking().OrderBy(x => x.EnvironmentKey).ToArrayAsync();
        pendingServers = (await db.Operations.AsNoTracking().Where(x => x.ServerId != null &&
            (x.Status == OperationStatus.Queued || x.Status == OperationStatus.Accepted || x.Status == OperationStatus.Running || x.Status == OperationStatus.NeedsAttention))
            .Select(x => x.ServerId!.Value).Distinct().ToArrayAsync()).ToHashSet();
        reservedResources = (await db.Reservations.AsNoTracking().Where(x => x.State != ReservationStatus.Released)
            .Select(x => x.ResourceKey).ToArrayAsync()).ToHashSet(StringComparer.Ordinal);
    }
    public HostResources Resources(InventoryHost host) => ControlJson.Deserialize<HostResources>(host.ResourcesJson);
    public ServerConfig Configuration(InventoryServer server) => ControlJson.Deserialize<ServerConfig>(server.DesiredConfigJson);
    public ControlDisplay.RuntimeStatus Status(InventoryServer server) => ControlDisplay.ServerState(Hosts.FirstOrDefault(h => h.HostId == server.HostId), runtime.GetValueOrDefault(server.ServerId), ObservedAtUtc);
    public ProcessControlAvailability Actions(InventoryServer server)
    {
        var config = Configuration(server);
        var pending = pendingServers.Contains(server.ServerId) || reservedResources.Contains(ReservationService.ServerKey(server.ServerId))
            || reservedResources.Any(key => ReservationService.Conflicts(key, ReservationService.DirectoryKey(server.HostId, config.ManagedRootId, config.ServerDirectory)))
            || (server.EnvironmentId.HasValue && reservedResources.Contains(ReservationService.EnvironmentKey(server.EnvironmentId.Value)));
        return ProcessControlAvailability.For(server, Hosts.FirstOrDefault(x => x.HostId == server.HostId), runtime.GetValueOrDefault(server.ServerId),
            ObservedAtUtc, configuration.GetValue<bool>("Control:EnableProcessControl"), pending);
    }
}
