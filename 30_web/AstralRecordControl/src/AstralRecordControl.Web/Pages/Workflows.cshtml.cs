using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Operations;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Pages;

[Authorize(Policy = "OwnerOnly")]
[ValidateAntiForgeryToken]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class WorkflowsModel(ControlDbContext db, ReservationService reservations, ControlOperationsService operations, ControlHistoryDeletionService deletion) : PageModel
{
    public sealed record WorkflowItem(ControlWorkflow Workflow, ControlReservationLease? Lease, HostReservationFence[] Fences, ControlOperation[] Operations, bool CanDelete);
    public WorkflowItem[] Recent { get; private set; } = [];
    public WorkflowItem? Selected { get; private set; }
    public WorkflowItem[] DeletionCandidates { get; private set; } = [];
    public int HistoryPage { get; private set; } = 1;
    public bool HasNextPage { get; private set; }
    public string? Error { get; private set; }
    public ControlOperation? ShutdownToRecover { get; private set; }
    public RecoveryInspection? ShutdownInspection { get; private set; }
    public Dictionary<Guid, string> ServerNames { get; private set; } = [];
    public Dictionary<Guid, string> HostNames { get; private set; } = [];
    [TempData] public string? Notice { get; set; }

    public async Task<IActionResult> OnGetAsync(Guid? workflowId, int historyPage = 1)
    {
        await LoadAsync(workflowId, historyPage);
        return workflowId.HasValue && Selected == null ? NotFound() : Page();
    }

    public async Task<IActionResult> OnPostConfirmDeleteAsync(Guid[] workflowIds, int historyPage = 1)
    {
        await LoadAsync(null, historyPage);
        if (!ModelState.IsValid || workflowIds.Length is < 1 or > 50 || workflowIds.Contains(Guid.Empty)) Error = "削除する履歴を1〜50件選択してください。";
        else
        {
            var rows = await db.Workflows.AsNoTracking().Where(x => workflowIds.Contains(x.WorkflowId) && x.HiddenAtUtc == null).ToArrayAsync();
            var candidates = await ItemsAsync(rows);
            if (candidates.Length != workflowIds.Distinct().Count() || candidates.Any(x => !x.CanDelete)) Error = "選択した履歴には削除できないものがあります。状態を確認してください。";
            else DeletionCandidates = candidates;
        }
        return Page();
    }

    public async Task<IActionResult> OnPostDeleteAsync(Guid[] workflowIds, bool confirmDelete, int historyPage = 1)
    {
        try
        {
            if (!ModelState.IsValid || !confirmDelete) throw new InventoryFault(400, "DeletionConfirmationRequired");
            var count = await deletion.DeleteAsync(workflowIds, HttpContext.RequestAborted);
            Notice = $"{count} 件の履歴と、管理Webに保存した付随ファイルを削除しました。";
            return RedirectToPage(new { historyPage });
        }
        catch (InventoryFault fault)
        {
            Error = fault.Message == "HistoryCleanupPending"
                ? "履歴は一覧から削除されました。付随ファイルの削除が未完了のため、自動で再試行します。"
                : "履歴を削除できませんでした。選択内容と、処理・予約の状態を確認してください。";
            await LoadAsync(null, historyPage);
            return Page();
        }
    }

    public async Task<IActionResult> OnPostRecoverAsync(Guid workflowId, WorkflowRecoveryAction action, string? reason, bool killConfirmed, Guid? inspectedSessionId)
    {
        try
        {
            if (!ModelState.IsValid) throw new InventoryFault(400, "InvalidInput");
            if (string.IsNullOrWhiteSpace(reason) || reason.Length > 1024 || reason.Any(c => c is '\r' or '\n' or '\0') || !Enum.IsDefined(action)) throw new InventoryFault(400, "InvalidInput");
            if (action == WorkflowRecoveryAction.KillAfterInspection && (!killConfirmed || inspectedSessionId == null || inspectedSessionId == Guid.Empty))
                throw new InventoryFault(400, "KillConfirmationRequired");
            var workflow = await db.Workflows.AsNoTracking().SingleOrDefaultAsync(x => x.WorkflowId == workflowId && x.HiddenAtUtc == null) ?? throw new InventoryFault(404, "NotFound");
            if (workflow.Kind == "Maintenance") await reservations.RecoverMaintenancePreparationAsync(workflowId, action, HttpContext.RequestAborted);
            else if (workflow.Kind == "SingleOperation") await operations.RecoverAsync(workflowId, new WorkflowRecoveryRequest(action, reason), HttpContext.RequestAborted, inspectedSessionId);
            else throw new InventoryFault(409, "UnsupportedCapability");
            return RedirectToPage(new { workflowId });
        }
        catch (Exception exception) when (IsRecoveryUnavailable(exception) || exception is ArgumentException)
        {
            Error = (exception as InventoryFault)?.Code switch
            {
                "KillConfirmationRequired" => "対象プロセスとセッションを確認し、強制終了の確認欄を選択してください。",
                "InvalidInput" => "点検理由を1〜1024文字の1行で入力してください。",
                "StateConflict" or "ReservationConflict" or "OperationConflict" => "処理または予約の状態が変わりました。表示された最新の状態を確認してください。",
                _ => "復旧を確定できませんでした。対象PCの接続、処理結果、プロセスの照合結果を確認してください。"
            };
            await LoadAsync(workflowId);
            return Page();
        }
    }

    private async Task LoadAsync(Guid? workflowId, int historyPage = 1)
    {
        HistoryPage = Math.Clamp(historyPage, 1, 1000000);
        var offset = (HistoryPage - 1) * 50;
        // Stored timestamps are UTC; order the bounded query as SQLite TEXT, as on the operations page.
        var rows = await db.Workflows.FromSqlInterpolated($"SELECT * FROM \"Workflows\" WHERE \"HiddenAtUtc\" IS NULL ORDER BY \"CreatedAtUtc\" DESC, \"WorkflowId\" DESC LIMIT 51 OFFSET {offset}").AsNoTracking().ToArrayAsync();
        HasNextPage = rows.Length > 50;
        Recent = await ItemsAsync(rows.Take(50).ToArray());
        Selected = null;
        if (workflowId.HasValue)
        {
            Selected = Recent.FirstOrDefault(x => x.Workflow.WorkflowId == workflowId);
            if (Selected == null)
            {
                var row = await db.Workflows.AsNoTracking().SingleOrDefaultAsync(x => x.WorkflowId == workflowId && x.HiddenAtUtc == null);
                if (row != null) Selected = (await ItemsAsync([row]))[0];
            }
        }
        ServerNames = await db.Servers.AsNoTracking().ToDictionaryAsync(x => x.ServerId, x => x.DisplayName);
        HostNames = await db.Hosts.AsNoTracking().ToDictionaryAsync(x => x.HostId, x => x.DisplayName);
        ShutdownToRecover = Selected is { Workflow.Kind: "SingleOperation", Lease.Status: ReservationStatus.RecoveryRequired } selected
            ? selected.Operations.SingleOrDefault(x => x.Kind is (OperationKind.Stop or OperationKind.Restart) && x.Status == OperationStatus.NeedsAttention)
            : null;
        ShutdownInspection = null;
        if (ShutdownToRecover is { } shutdown && Selected!.Operations.All(x => x.Kind != OperationKind.Kill))
        {
            try { ShutdownInspection = await operations.InspectShutdownRecoveryAsync(shutdown, HttpContext.RequestAborted); }
            catch (Exception exception) when (IsRecoveryUnavailable(exception)) { }
        }
    }

    public bool CanRescueShutdown => ShutdownToRecover is { } shutdown && ShutdownInspection is { } inspection &&
        ControlOperationsService.IsSameShutdownProcess(shutdown, inspection);

    private bool IsRecoveryUnavailable(Exception exception) => exception is InventoryFault or IOException or HubException or TimeoutException ||
        exception is OperationCanceledException && !HttpContext.RequestAborted.IsCancellationRequested;

    private async Task<WorkflowItem[]> ItemsAsync(ControlWorkflow[] rows)
    {
        var ids = rows.Select(x => x.WorkflowId).ToArray();
        var leases = (await db.ReservationLeases.AsNoTracking().Where(x => ids.Contains(x.WorkflowId)).ToArrayAsync()).ToDictionary(x => x.WorkflowId);
        var children = await db.Operations.AsNoTracking().Where(x => ids.Contains(x.WorkflowId)).ToArrayAsync();
        var holds = await db.Reservations.AsNoTracking().Where(x => ids.Contains(x.OwnerWorkflowId)).ToArrayAsync();
        return rows.Select(x =>
        {
            leases.TryGetValue(x.WorkflowId, out var lease);
            var ops = children.Where(o => o.WorkflowId == x.WorkflowId).OrderBy(o => o.CreatedAtUtc).ToArray();
            return new WorkflowItem(x, lease, lease == null ? [] : ControlJson.Deserialize<HostReservationFence[]>(lease.FencesJson), ops,
                ControlHistoryDeletionService.CanDelete(x, lease, ops, holds.Where(r => r.OwnerWorkflowId == x.WorkflowId).ToArray()));
        }).ToArray();
    }

    public string Title(WorkflowItem item) => item.Workflow.Kind == "Maintenance" ? "保守作業" : item.Operations.Length == 1 ? Shared.ControlDisplay.OperationName(item.Operations[0].Kind) : "運転操作";
    public string Target(WorkflowItem item)
    {
        var targets = item.Operations.Select(o => o.ServerId.HasValue ? ServerNames.GetValueOrDefault(o.ServerId.Value, "削除済みサーバー") : HostNames.GetValueOrDefault(o.HostId, "PC未確認")).Distinct().ToArray();
        if (targets.Length > 0) return string.Join("、", targets);
        var resources = ControlJson.Deserialize<string[]>(item.Lease?.ResourceKeysJson ?? item.Workflow.ResourceKeysJson);
        var names = resources.Select(key =>
        {
            var parts = key.Split(':', 2);
            if (parts.Length != 2 || !Guid.TryParse(parts[1], out var id)) return null;
            return parts[0] switch { "server" => ServerNames.GetValueOrDefault(id, "削除済みサーバー"), "host" => HostNames.GetValueOrDefault(id, "PC未確認"), _ => null };
        }).Where(name => name != null).Distinct().ToArray();
        var identity = item.Lease?.RunReference ?? item.Workflow.WorkflowId.ToString();
        return names.Length > 0 ? $"{string.Join("、", names)} · {identity}" : identity;
    }
}
