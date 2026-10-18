using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Operations;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Pages;

[Authorize(Policy = "OwnerOnly")]
[ValidateAntiForgeryToken]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class WorkflowsModel(ControlDbContext db, ReservationService reservations, ControlOperationsService operations) : PageModel
{
    public sealed record WorkflowItem(ControlWorkflow Workflow, ControlReservationLease? Lease, HostReservationFence[] Fences, ControlOperation[] Operations);

    public WorkflowItem[] Recent { get; private set; } = [];
    public Guid? SelectedWorkflowId { get; private set; }
    public string? Error { get; private set; }

    public async Task<IActionResult> OnGetAsync(Guid? workflowId)
    {
        await LoadAsync(workflowId);
        return Page();
    }

    public async Task<IActionResult> OnPostRecoverAsync(Guid workflowId, WorkflowRecoveryAction action, string? reason, bool killConfirmed)
    {
        try
        {
            if (!ModelState.IsValid) throw new InventoryFault(400, "InvalidInput");
            if (string.IsNullOrWhiteSpace(reason) || reason.Length > 1024 || reason.Any(c => c is '\r' or '\n' or '\0') || !Enum.IsDefined(action)) throw new InventoryFault(400, "InvalidInput");
            if (action == WorkflowRecoveryAction.KillAfterInspection && !killConfirmed) throw new InventoryFault(400, "KillConfirmationRequired");
            var workflow = await db.Workflows.AsNoTracking().SingleOrDefaultAsync(x => x.WorkflowId == workflowId) ?? throw new InventoryFault(404, "NotFound");
            if (workflow.Kind == "Maintenance") await reservations.RecoverMaintenancePreparationAsync(workflowId, action, HttpContext.RequestAborted);
            else if (workflow.Kind == "SingleOperation") await operations.RecoverAsync(workflowId, new WorkflowRecoveryRequest(action, reason), HttpContext.RequestAborted);
            else throw new InventoryFault(409, "UnsupportedCapability");
            return RedirectToPage(new { workflowId });
        }
        catch (Exception exception) when (exception is InventoryFault or IOException or ArgumentException)
        {
            Error = exception.Message;
            await LoadAsync(workflowId);
            return Page();
        }
    }

    private async Task LoadAsync(Guid? workflowId)
    {
        SelectedWorkflowId = workflowId;
        // SQLite cannot order DateTimeOffset columns. Sort the bounded result in memory.
        var recent = (await db.Workflows.AsNoTracking().ToArrayAsync()).OrderByDescending(x => x.CreatedAtUtc).Take(50).ToList();
        if (workflowId.HasValue && recent.All(x => x.WorkflowId != workflowId.Value))
        {
            var selected = await db.Workflows.AsNoTracking().SingleOrDefaultAsync(x => x.WorkflowId == workflowId.Value);
            if (selected != null) recent.Insert(0, selected);
        }
        var ids = recent.Select(x => x.WorkflowId).ToArray();
        var leases = (await db.ReservationLeases.AsNoTracking().Where(x => ids.Contains(x.WorkflowId)).ToArrayAsync()).ToDictionary(x => x.WorkflowId);
        var children = (await db.Operations.AsNoTracking().Where(x => ids.Contains(x.WorkflowId)).ToArrayAsync()).GroupBy(x => x.WorkflowId).ToDictionary(x => x.Key, x => x.OrderBy(y => y.CreatedAtUtc).ToArray());
        Recent = recent.Select(x =>
        {
            leases.TryGetValue(x.WorkflowId, out var lease);
            return new WorkflowItem(x, lease, lease == null ? [] : ControlJson.Deserialize<HostReservationFence[]>(lease.FencesJson), children.GetValueOrDefault(x.WorkflowId) ?? []);
        }).ToArray();
    }
}
