using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Notifications;

public sealed class NotificationRetentionService(ControlDbContext db, InventoryStore store, TimeProvider clock)
{
    public async Task RunAsync(CancellationToken cancellationToken = default)
    {
        // Only terminal delivery rows are removed. AgentEvents (ACK replay evidence),
        // pending outbox entries, and nonterminal operations are intentionally untouched.
        var cutoff = clock.GetUtcNow().AddDays(-90);
        var expired = await db.NotificationOutbox.AsNoTracking()
            .Where(x => x.TerminalAtUnixMs != null && x.TerminalAtUnixMs < cutoff.ToUnixTimeMilliseconds())
            .OrderBy(x => x.TerminalAtUnixMs).Take(1000).ToArrayAsync(cancellationToken);
        await store.WriteAsync(async () =>
        {
            foreach (var row in expired)
            {
                var live = await db.NotificationOutbox.SingleOrDefaultAsync(x => x.EventId == row.EventId && x.DestinationProfileId == row.DestinationProfileId, cancellationToken);
                if (live?.TerminalAtUnixMs < cutoff.ToUnixTimeMilliseconds()) db.NotificationOutbox.Remove(live);
            }
            await db.SaveChangesAsync(cancellationToken);
            var oldEvents = await db.NotificationEvents
                .Where(x => x.RecordedAtUnixMs < cutoff.ToUnixTimeMilliseconds())
                .OrderBy(x => x.RecordedAtUnixMs).Take(1000).ToArrayAsync(cancellationToken);
            foreach (var row in oldEvents)
            {
                if (!await db.NotificationOutbox.AnyAsync(x => x.EventId == row.EventId, cancellationToken)) db.NotificationEvents.Remove(row);
            }
            return true;
        });
    }
}

public sealed class NotificationRetentionWorker(IServiceScopeFactory scopes, ILogger<NotificationRetentionWorker> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                using var scope = scopes.CreateScope();
                await scope.ServiceProvider.GetRequiredService<NotificationRetentionService>().RunAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception ex) { logger.LogWarning("Notification retention cycle failed: {ErrorType}", ex.GetType().Name); }
            await Task.Delay(TimeSpan.FromHours(1), stoppingToken);
        }
    }
}
