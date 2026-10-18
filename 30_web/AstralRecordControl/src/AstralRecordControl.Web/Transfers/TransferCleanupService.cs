using Microsoft.Extensions.DependencyInjection;

namespace AstralRecordControl.Web.Transfers;

public sealed class TransferCleanupService(IServiceScopeFactory scopes, TimeProvider clock) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromMinutes(30), clock);
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            using var scope = scopes.CreateScope();
            await scope.ServiceProvider.GetRequiredService<TransferRelay>().CleanupAsync(stoppingToken);
        }
    }
}
