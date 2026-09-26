using AstralRecordApi.Repositories;

namespace AstralRecordApi.Services;

/// <summary>承認済みの永続配布指示と新規アカウントへの累計配布を再試行します。</summary>
public sealed class DonationDeliveryHostedService(IServiceScopeFactory scopes, IConfiguration configuration,
    ILogger<DonationDeliveryHostedService> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(2));
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            if (string.IsNullOrWhiteSpace(configuration["Donations:WebKey"])) continue;
            try
            {
                using var scope = scopes.CreateScope();
                await scope.ServiceProvider.GetRequiredService<IDonationRepository>().ReconcileAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception ex)
            {
                // 支払情報・OAuthトークンを例外メッセージから漏らさない。
                logger.LogWarning("寄付メールの照合に失敗しました。次回再試行します。種類: {ErrorType}", ex.GetType().Name);
                await Task.Delay(TimeSpan.FromSeconds(30), stoppingToken);
            }
        }
    }
}
