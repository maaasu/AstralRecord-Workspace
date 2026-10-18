using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Operations;

public sealed class ControlHistoryRetention(ControlDbContext db, InventoryStore store, OperationResultStore results, TimeProvider clock)
{
    public async Task RunAsync(CancellationToken token = default)
    {
        var cutoff = clock.GetUtcNow().AddDays(-90);
        var retired = await store.WriteAsync(async () =>
        {
            var ids = new List<Guid>();
            var candidates = await db.Operations.Where(o => o.FinishedAtUtc != null).ToArrayAsync(token);
            foreach (var operation in candidates.Where(o => o.FinishedAtUtc < cutoff && OperationValidation.IsTerminal(o.Status)))
            {
                var workflow = await db.Workflows.FindAsync([operation.WorkflowId], token);
                var lease = await db.ReservationLeases.FindAsync([operation.ReservationId], token);
                if (workflow == null || workflow.RecoveryRequired || workflow.Status is not (WorkflowStatus.Succeeded or WorkflowStatus.Failed or WorkflowStatus.Cancelled) || lease?.Status != ReservationStatus.Released) continue;
                // Keep immutable identity/hash/status tombstones for Agent replay and owner idempotency.
                operation.CommandJson = ""; operation.ResultJson = null;
                ids.Add(operation.OperationId);
                var related = await db.Operations.Where(o => o.WorkflowId == workflow.WorkflowId).ToArrayAsync(token);
                if (related.All(o => o.FinishedAtUtc < cutoff && OperationValidation.IsTerminal(o.Status))) workflow.RequestJson = "";
            }
            var events = await db.AgentEvents.Where(e => e.Json != "").ToArrayAsync(token);
            foreach (var item in events.Where(e => e.CreatedAtUtc < cutoff))
            {
                var host = await db.Hosts.FindAsync([item.HostId], token);
                if (host != null && item.EventSeq <= host.AckAgentEventSeq) item.Json = ""; // Sha256 survives exact duplicate ACK replay.
            }
            foreach (var row in (await db.ScheduleOccurrences.Where(r => r.ReportJson != "").ToArrayAsync(token)).Where(r => r.UpdatedAtUtc < cutoff))
            {
                var report = ControlJson.Deserialize<ScheduleOccurrenceReport>(row.ReportJson);
                if (!InventoryStore.IsScheduleTerminal(report.Status)) continue;
                var host = await db.Hosts.FindAsync([row.HostId], token);
                if (row.AckedAgentEventSeq < 1 || host == null || row.AckedAgentEventSeq > host.AckAgentEventSeq) continue;
                row.ReportJson = "";
                var manual = await db.ScheduleManualRequests.FindAsync([row.OperationId], token);
                if (manual != null) manual.DefinitionJson = "";
            }
            foreach (var request in (await db.ScheduleManualRequests.Where(r => r.RejectionCode != null && r.DefinitionJson != "").ToArrayAsync(token)).Where(r => r.CreatedAtUtc < cutoff)) request.DefinitionJson = "";
            return ids;
        });
        foreach (var id in retired) await results.RemoveRetiredAsync(id, token);
    }
}

public sealed class ControlHistoryRetentionWorker(IServiceScopeFactory scopes, TimeProvider clock, ILogger<ControlHistoryRetentionWorker> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromHours(1), clock);
        do
        {
            try
            {
                await using var scope = scopes.CreateAsyncScope();
                await scope.ServiceProvider.GetRequiredService<ControlHistoryRetention>().RunAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception error) when (error is IOException or DbUpdateException or InventoryFault)
            { logger.LogWarning("Control history retention could not finish ({ErrorCode}).", "RetentionUnavailable"); }
        } while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}
