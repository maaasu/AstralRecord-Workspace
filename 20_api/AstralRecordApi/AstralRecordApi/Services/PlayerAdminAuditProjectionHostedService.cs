using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Services;

/// <summary>ゲームDBの確定領収書をManagementDBへ冪等投影します。</summary>
public sealed class PlayerAdminAuditProjectionHostedService(
    IServiceScopeFactory scopeFactory,
    ILogger<PlayerAdminAuditProjectionHostedService> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(30));
        do
        {
            try { await ProjectBatchAsync(stoppingToken); }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception exception)
            {
                logger.LogWarning(exception, "Player admin audit projection will retry.");
            }
        }
        while (await timer.WaitForNextTickAsync(stoppingToken));
    }

    private async Task ProjectBatchAsync(CancellationToken token)
    {
        await using var scope = scopeFactory.CreateAsyncScope();
        var game = scope.ServiceProvider.GetRequiredService<AstralRecordDbContext>();
        var management = scope.ServiceProvider.GetRequiredService<ManagementDbContext>();
        var pending = await game.PlayerAdminEditOperations.AsNoTracking()
            .Where(x => x.AuditProjectedAtUtc == null)
            .OrderBy(x => x.CreatedAtUtc).Take(50).ToListAsync(token);
        foreach (var operation in pending)
        {
            token.ThrowIfCancellationRequested();
            var session = await game.PlayerAdminEditSessions.AsNoTracking()
                .SingleOrDefaultAsync(x => x.EditSessionId == operation.EditSessionId, token);
            if (session is null)
            {
                logger.LogError("Player admin audit source session {EditSessionId} is missing.", operation.EditSessionId);
                continue;
            }
            if (!await management.PlayerAdminEditAudits.AsNoTracking()
                .AnyAsync(x => x.OperationId == operation.OperationId, token))
            {
                management.PlayerAdminEditAudits.Add(new PlayerAdminEditAuditEntity
                {
                    OperationId = operation.OperationId, EditSessionId = operation.EditSessionId,
                    AccountId = session.AccountId, TargetUserUuid = session.UserUuid,
                    ActorUserUuid = session.ActorUserUuid, Action = operation.Action,
                    Reason = session.Reason,
                    RequestHash = operation.RequestHash, BeforeJson = operation.BeforeJson,
                    AfterJson = operation.AfterJson, OccurredAtUtc = operation.CreatedAtUtc,
                    ProjectionStatus = "PROJECTED",
                });
                await management.SaveChangesAsync(token);
            }
            await game.PlayerAdminEditOperations.Where(x => x.OperationId == operation.OperationId
                    && x.AuditProjectedAtUtc == null)
                .ExecuteUpdateAsync(x => x.SetProperty(row => row.AuditProjectedAtUtc, DateTime.UtcNow), token);
        }
    }
}
