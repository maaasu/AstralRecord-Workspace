using AstralRecordApi.Data;
using AstralRecordApi.Options;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace AstralRecordApi.Services;

/// <summary>API 起動時に MasterDataDB の Seeder を実行するホステッドサービス。</summary>
public class MasterDataSeedHostedService(
    IServiceProvider services,
    IOptions<MasterDataOptions> options,
    ILogger<MasterDataSeedHostedService> logger) : IHostedService
{
    public async Task StartAsync(CancellationToken cancellationToken)
    {
        using var scope = services.CreateScope();
        var seeder = scope.ServiceProvider.GetRequiredService<IMasterDataSeeder>();
        var dbContext = scope.ServiceProvider.GetRequiredService<MasterDataDbContext>();

        try
        {
            var shouldSeed = options.Value.AutoSeedOnStartup
                || !await dbContext.Entries.AnyAsync(entry => !entry.IsDeleted, cancellationToken);

            if (!shouldSeed)
            {
                logger.LogInformation("MasterDataDB は投入済みのため起動時 Seeder をスキップします。");
                return;
            }

            var result = await seeder.RunAsync(
                MasterDataSeedTrigger.Startup, MasterDataSeedMode.Diff, cancellationToken);

            if (result.Status == "FAILED")
            {
                logger.LogError(
                    "起動時 Seeder が失敗しました。API は degraded 状態で起動します: {Error}",
                    result.ErrorMessage);
            }
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "起動時 Seeder の実行中に例外が発生しました。API は degraded 状態で起動します。");
        }
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;
}
