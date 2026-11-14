namespace SkillTreeEditor.Server.Services;

/// <summary>
/// 起動直後に Filebase とスキルツリーを読み込んでおき、最初に画面を開いたときの待ち時間を減らす。
/// 失敗しても起動は続け、同じ問題は画面からの要求で通常どおり報告される。
/// </summary>
public sealed class EditorWarmup(FilebaseSnapshotCache snapshots, FilebaseRepository repository, ILogger<EditorWarmup> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        try
        {
            await snapshots.GetAsync(stoppingToken);
            await repository.ReadNodesAsync(stoppingToken);
            await repository.ReadStructuresAsync(stoppingToken);
        }
        catch (Exception exception)
        {
            if (!stoppingToken.IsCancellationRequested)
                logger.LogDebug(exception, "起動時の先読みを完了できませんでした。");
        }
    }
}
