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
                var reservations = scope.ServiceProvider.GetRequiredService<ReservationService>();
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
                    var lease = await reservations.GetAsync(workflowId);
                    var children = await db.Operations.AsNoTracking().Where(x => x.WorkflowId == workflowId).ToArrayAsync(stoppingToken);
                    if (children.Length == 0 || children.Any(x => !OperationValidation.IsTerminal(x.Status))) continue;
                    var outcome = children.All(x => x.Status == OperationStatus.Succeeded) ? GateOutcome.Succeeded : GateOutcome.Failed;
                    try
                    {
                        if (lease.Status == ReservationStatus.Prepared) await reservations.ReleaseAsync(lease.ReservationId, new GateReleaseRequest(workflowId, lease.Fences, outcome, ""), stoppingToken);
                        else if (lease.Status == ReservationStatus.RecoveryRequired && children.Any(x => x.Kind == OperationKind.Kill && x.Status == OperationStatus.Succeeded)) await reservations.ReleaseInspectedAsync(lease.ReservationId, workflowId, lease.Fences, outcome, stoppingToken);
                        else if (lease.Status == ReservationStatus.RecoveryRequired && children.Length == 1 && children[0].Kind is OperationKind.RestoreBackup or OperationKind.CreateBackup && children[0].Status == OperationStatus.Succeeded && children[0].ServerId is Guid serverId)
                        {
                            var agents = scope.ServiceProvider.GetRequiredService<IAgentOperationTransport>();
                            var inspection = await agents.InspectRecoveryAsync(children[0].HostId, serverId, stoppingToken);
                            if (!inspection.RecoveryRequired && (!inspection.ProcessAlive && inspection.Runtime.ProcessState == ProcessState.Stopped && inspection.Runtime.CurrentSessionId == null || inspection.ProcessAlive && inspection.IdentityMatches && inspection.Runtime.ProcessState == ProcessState.Running && inspection.Runtime.CurrentSessionId == inspection.Session?.ServerSessionId))
                                await reservations.ReleaseInspectedAsync(lease.ReservationId, workflowId, lease.Fences, outcome, stoppingToken);
                        }
                    }
                    catch (Exception) when (!stoppingToken.IsCancellationRequested) { logger.LogWarning("Operation reservation release needs inspection for workflow {WorkflowId}.", workflowId); }
                }
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception) { logger.LogError("Operation lifecycle reconciliation failed."); }
        }
    }
}
