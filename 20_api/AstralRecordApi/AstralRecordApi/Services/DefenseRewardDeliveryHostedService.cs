using AstralRecordApi.Repositories;

namespace AstralRecordApi.Services;

/// <summary>ManagementDBへ確定済みの報酬を、障害後も既存メールへ再配送します。</summary>
public sealed class DefenseRewardDeliveryHostedService(IServiceScopeFactory scopes,
    ILogger<DefenseRewardDeliveryHostedService> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(30));
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                using var scope = scopes.CreateScope();
                var defense = scope.ServiceProvider.GetRequiredService<IDefenseRepository>();
                await defense.ReconcileScheduleAsync(stoppingToken);
                await defense.DeliverPendingAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception ex) { logger.LogError(ex, "防衛戦の確定報酬メール配送に失敗しました。"); }
            if (!await timer.WaitForNextTickAsync(stoppingToken)) break;
        }
    }
}
