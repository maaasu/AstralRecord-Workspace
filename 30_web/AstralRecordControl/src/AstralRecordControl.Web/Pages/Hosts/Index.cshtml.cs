using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Authentication;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Hubs;
using AstralRecordControl.Web.Inventory;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Pages.Hosts;

[Authorize(Policy = "OwnerOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class IndexModel(ControlDbContext db, InventoryStore store, AgentCredentialService credentials, AgentConnectionRegistry connections, InventoryNotifier notifier, TimeProvider clock) : PageModel
{
    public Dictionary<Guid, int> ServerCounts { get; private set; } = [];
    public InventoryHost[] Hosts { get; private set; } = [];
    public InventoryEnvironment[] Environments { get; private set; } = [];
    public CredentialView[] Credentials { get; private set; } = [];
    public CredentialIssued? Issued { get; private set; }
    public string? Error { get; private set; }
    public Guid RequestKey { get; private set; } = Guid.NewGuid();
    public async Task OnGetAsync() => await LoadAsync();
    public async Task<IActionResult> OnPostCreateAsync(string hostKey, string displayName, Guid requestKey)
    {
        try { var body = new HostCreateRequest(hostKey, displayName); await store.CreateHostAsync(body, requestKey, ControlJson.Serialize(body)); return RedirectToPage(); }
        catch (Exception exception) when (exception is InventoryFault or ArgumentException) { Error = exception.Message; await LoadAsync(); return Page(); }
    }
    public async Task<IActionResult> OnPostEnvironmentAsync(string environmentKey, string displayName, Guid requestKey)
    {
        try { var body = new EnvironmentCreateRequest(environmentKey, displayName); await store.CreateEnvironmentAsync(body, requestKey, ControlJson.Serialize(body)); return RedirectToPage(); }
        catch (Exception exception) when (exception is InventoryFault or ArgumentException) { Error = exception.Message; await LoadAsync(); return Page(); }
    }
    public async Task<IActionResult> OnPostUpdateAsync(Guid hostId, long revision, string displayName, bool enabled)
    {
        try
        {
            await store.UpdateHostAsync(hostId, new(displayName, enabled), revision);
            if (!enabled) connections.RevokeHost(hostId);
            await notifier.StateChangedAsync(); return RedirectToPage();
        }
        catch (InventoryFault fault) { Error = fault.Code; await LoadAsync(); return Page(); }
    }
    public async Task<IActionResult> OnPostIssueAsync(Guid hostId)
    {
        try { Issued = await credentials.IssueAsync(hostId); }
        catch (InventoryFault fault) { Error = fault.Code; }
        await LoadAsync(); return Page();
    }
    public async Task<IActionResult> OnPostRevokeAsync(Guid hostId, Guid credentialId)
    {
        try { await credentials.RevokeAsync(hostId, credentialId); return RedirectToPage(); }
        catch (InventoryFault fault) { Error = fault.Code; await LoadAsync(); return Page(); }
    }
    private async Task LoadAsync()
    {
        Hosts = await db.Hosts.AsNoTracking().OrderBy(x => x.HostKey).ToArrayAsync();
        foreach (var host in Hosts)
            if (host.Enabled && host.ConnectionState is HostConnectionState.Online or HostConnectionState.Reconciling &&
                (!host.LastSeenAtUtc.HasValue || clock.GetUtcNow() - host.LastSeenAtUtc > TimeSpan.FromSeconds(30))) host.ConnectionState = HostConnectionState.Offline;
        ServerCounts = await db.Servers.AsNoTracking().GroupBy(x => x.HostId).ToDictionaryAsync(x => x.Key, x => x.Count());
        Environments = await db.Environments.AsNoTracking().OrderBy(x => x.EnvironmentKey).ToArrayAsync();
        Credentials = await db.AgentCredentials.AsNoTracking().Select(x => new CredentialView(x.HostId, x.CredentialId, x.IssuedAtUtc, x.RevokedAtUtc)).ToArrayAsync();
    }
    public sealed record CredentialView(Guid HostId, Guid CredentialId, DateTimeOffset IssuedAtUtc, DateTimeOffset? RevokedAtUtc);
}
