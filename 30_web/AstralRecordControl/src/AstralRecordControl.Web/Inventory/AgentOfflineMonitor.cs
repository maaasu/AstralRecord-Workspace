using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Hubs;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Inventory;

public sealed class AgentOfflineMonitor(IServiceScopeFactory scopes, TimeProvider clock, AgentConnectionRegistry connections, ILogger<AgentOfflineMonitor> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(5), clock);
        try
        {
            await SweepAsync();
            while (await timer.WaitForNextTickAsync(stoppingToken))
            {
                try { await SweepAsync(); }
                catch (Exception exception) when (!stoppingToken.IsCancellationRequested) { logger.LogWarning(exception, "Agent offline monitor could not persist state."); }
            }
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { }
    }
    public async Task SweepAsync()
    {
        using var scope = scopes.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<ControlDbContext>();
        var store = scope.ServiceProvider.GetRequiredService<InventoryStore>();
        var changed = await store.WriteAsync(async () =>
        {
            var hosts = await db.Hosts.Where(h => h.ConnectionState == HostConnectionState.Online || h.ConnectionState == HostConnectionState.Reconciling).ToArrayAsync();
            var expired = hosts.Where(h => connections.Current(h.HostId) == null || h.LastSeenAtUtc == null || clock.GetUtcNow() - h.LastSeenAtUtc > TimeSpan.FromSeconds(30)).ToArray();
            foreach (var host in expired)
            {
                host.ConnectionState = HostConnectionState.Offline; host.ConnectionEpoch = null;
                connections.RevokeHost(host.HostId);
            }
            return expired.Length > 0;
        });
        if (changed) await scope.ServiceProvider.GetRequiredService<InventoryNotifier>().StateChangedAsync();
    }
}
