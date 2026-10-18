using AstralRecordControl.Web.Inventory;

namespace AstralRecordControl.Web.Metrics;

public sealed class MetricsRetentionService(IServiceScopeFactory scopes, TimeProvider clock,
    ILogger<MetricsRetentionService> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromHours(1), clock);
        try
        {
            await SweepSafelyAsync(stoppingToken);
            while (await timer.WaitForNextTickAsync(stoppingToken))
                await SweepSafelyAsync(stoppingToken);
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { }
    }

    private async Task SweepSafelyAsync(CancellationToken stoppingToken)
    {
        try { await SweepAsync(); }
        catch (Exception exception) when (!stoppingToken.IsCancellationRequested)
        { logger.LogWarning(exception, "Metrics retention sweep failed."); }
    }

    public async Task SweepAsync()
    {
        using var scope = scopes.CreateScope();
        var store = scope.ServiceProvider.GetRequiredService<InventoryStore>();
        var metrics = scope.ServiceProvider.GetRequiredService<MetricsStore>();
        await store.WriteAsync(async () => { await metrics.PruneAsync(); return true; });
    }
}
