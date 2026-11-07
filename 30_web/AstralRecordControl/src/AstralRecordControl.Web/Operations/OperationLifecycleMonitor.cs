using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Operations;

public sealed class OperationLifecycleMonitor(IServiceScopeFactory scopes, ILogger<OperationLifecycleMonitor> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(10));
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            try
            {
                using var scope = scopes.CreateScope();
                var db = scope.ServiceProvider.GetRequiredService<ControlDbContext>();
                var operations = scope.ServiceProvider.GetRequiredService<ControlOperationsService>();
                var pending = await db.Operations.AsNoTracking().Where(x => x.Status != OperationStatus.Succeeded && x.Status != OperationStatus.Failed && x.Status != OperationStatus.Rejected && x.Status != OperationStatus.Expired && x.Status != OperationStatus.Cancelled).Select(x => x.OperationId).ToArrayAsync(stoppingToken);
                foreach (var operationId in pending)
                {
                    try { await operations.RefreshAsync(operationId, stoppingToken); }
                    catch (Exception) when (!stoppingToken.IsCancellationRequested) { }
                }
                // Repair a Web crash after persisting Succeeded but before consuming the artifact.
                var consumedOperationIds = await db.Transfers.AsNoTracking().Where(x => x.Status == TransferStatus.Verified && x.ConsumingOperationId != null).Select(x => x.ConsumingOperationId!.Value).ToArrayAsync(stoppingToken);
                foreach (var operationId in consumedOperationIds)
                {
                    try { await operations.RefreshAsync(operationId, stoppingToken); }
                    catch (Exception) when (!stoppingToken.IsCancellationRequested) { }
                }
                var activeWorkflowIds = (await db.Workflows.AsNoTracking().Where(x => x.Kind == "SingleOperation" && (x.Status == WorkflowStatus.Running || x.Status == WorkflowStatus.RecoveryRequired)).Select(x => new { x.WorkflowId, x.CreatedAtUtc }).ToArrayAsync(stoppingToken)).OrderBy(x => x.CreatedAtUtc).Select(x => x.WorkflowId).ToArray();
                foreach (var workflowId in activeWorkflowIds)
                {
                    try { await operations.ReconcileSingleOperationReleaseAsync(workflowId, stoppingToken); }
                    catch (Exception) when (!stoppingToken.IsCancellationRequested) { logger.LogWarning("Operation reservation release needs inspection for workflow {WorkflowId}.", workflowId); }
                }
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception) { logger.LogError("Operation lifecycle reconciliation failed."); }
        }
    }
}
