using AstralRecordApi.Repositories;

namespace AstralRecordApi.Services;

/// <summary>掲載期限を過ぎた出品を購入対象から外し、返却待ちにします。</summary>
public sealed class MarketExpirationHostedService(
    IServiceScopeFactory scopes,
    ILogger<MarketExpirationHostedService> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(5));
        do
        {
            try
            {
                int processed;
                do
                {
                    using var scope = scopes.CreateScope();
                    processed = await scope.ServiceProvider.GetRequiredService<IMarketRepository>()
                        .MarkExpiredListingsAsync(stoppingToken);
                } while (processed == 100 && !stoppingToken.IsCancellationRequested);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception error)
            {
                logger.LogWarning(error, "マーケット期限切れ処理に失敗しました。次回再試行します。");
            }
        } while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}
