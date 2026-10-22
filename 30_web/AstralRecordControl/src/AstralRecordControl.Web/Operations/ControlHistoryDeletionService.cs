using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Operations;

public sealed class ControlHistoryDeletionService(ControlDbContext db, InventoryStore store, OperationResultStore results, TimeProvider clock)
{
    public static bool CanDelete(ControlWorkflow workflow, ControlReservationLease? lease, IReadOnlyCollection<ControlOperation> operations, IReadOnlyCollection<ControlReservation> reservations) =>
        !workflow.RecoveryRequired
        && workflow.Status is WorkflowStatus.Succeeded or WorkflowStatus.Failed or WorkflowStatus.Cancelled
        && lease?.Status == ReservationStatus.Released
        && !reservations.Any(r => r.OwnerWorkflowId == workflow.WorkflowId && r.State != ReservationStatus.Released)
        && operations.All(o => o.WorkflowId != workflow.WorkflowId || OperationValidation.IsTerminal(o.Status) && o.FinishedAtUtc != null);

    public async Task<int> DeleteAsync(IReadOnlyCollection<Guid> workflowIds, CancellationToken token = default)
    {
        if (workflowIds.Count is < 1 or > 100 || workflowIds.Any(id => id == Guid.Empty) || workflowIds.Distinct().Count() != workflowIds.Count)
            throw new InventoryFault(400, "InvalidInput");

        var selected = workflowIds.ToArray();
        var deleted = await store.WriteAsync(async () =>
        {
            var workflows = await db.Workflows.Where(w => selected.Contains(w.WorkflowId)).ToArrayAsync(token);
            if (workflows.Length != selected.Length) throw new InventoryFault(404, "NotFound");
            var operations = await db.Operations.Where(o => selected.Contains(o.WorkflowId)).ToArrayAsync(token);
            var leases = await db.ReservationLeases.Where(l => selected.Contains(l.WorkflowId)).ToArrayAsync(token);
            var reservations = await db.Reservations.Where(r => selected.Contains(r.OwnerWorkflowId)).ToArrayAsync(token);

            foreach (var workflow in workflows)
            {
                if (leases.Count(l => l.WorkflowId == workflow.WorkflowId) != 1
                    || !CanDelete(workflow, leases.Single(l => l.WorkflowId == workflow.WorkflowId), operations, reservations))
                    throw new InventoryFault(409, "HistoryInUse");
            }

            var now = clock.GetUtcNow();
            var newlyHidden = 0;
            foreach (var workflow in workflows)
            {
                if (workflow.HiddenAtUtc.HasValue) continue;
                workflow.HiddenAtUtc = now;
                workflow.RequestJson = "";
                newlyHidden++;
            }
            foreach (var operation in operations)
            {
                operation.CommandJson = "";
                operation.ResultJson = null;
            }
            return (newlyHidden, operations.Select(o => o.OperationId).ToArray());
        });

        var cleanupPending = false;
        foreach (var operationId in deleted.Item2)
        {
            try { await results.RemoveRetiredAsync(operationId, token); }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or InventoryFault)
            {
                cleanupPending = true;
            }
        }
        if (cleanupPending)
        {
            // The committed tombstone prevents replay; retention retries physical cleanup.
            throw new InventoryFault(503, "HistoryCleanupPending");
        }
        return deleted.Item1;
    }
}
