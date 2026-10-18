using System.Globalization;
using System.Text.Json;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Hubs;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Operations;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordControl.Web.Pages.Servers;

[Authorize(Policy = "OwnerOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class SchedulesModel(InventoryStore store, IAgentOperationTransport agents, InventoryNotifier notifier, IConfiguration configuration) : PageModel
{
    public InventoryServer Server { get; private set; } = null!;
    public ScheduleView[] Schedules { get; private set; } = [];
    public Dictionary<Guid, ScheduleOccurrenceReport[]> History { get; } = [];
    public ScheduleManualRequest[] PendingRequests { get; private set; } = [];
    public bool Available => configuration.GetValue<bool>("Control:EnableSchedules");
    [BindProperty] public Guid RequestKey { get; set; }
    [BindProperty] public Guid? ScheduleId { get; set; }
    [BindProperty] public long? Revision { get; set; }
    [BindProperty] public bool Enabled { get; set; } = true;
    [BindProperty] public ScheduleTriggerKind TriggerKind { get; set; }
    [BindProperty] public string AtUtc { get; set; } = "";
    [BindProperty] public int Seconds { get; set; } = 3600;
    [BindProperty] public string Expression { get; set; } = "0 4 * * *";
    [BindProperty] public string TimeZoneId { get; set; } = "Asia/Tokyo";
    [BindProperty] public string StepsJson { get; set; } = "[{\"stepKey\":\"restart\",\"kind\":\"Restart\",\"payload\":{},\"delaySeconds\":0}]";
    [TempData] public string? Message { get; set; }

    public async Task<IActionResult> OnGetAsync(Guid serverId, Guid? edit = null)
    {
        await LoadAsync(serverId);
        RequestKey = Guid.NewGuid();
        if (edit.HasValue)
        {
            var definition = Schedules.SingleOrDefault(s => s.Definition.ScheduleId == edit)?.Definition;
            if (definition == null) return NotFound();
            ScheduleId = definition.ScheduleId; Revision = definition.Revision; Enabled = definition.Enabled;
            TriggerKind = definition.Trigger.Kind; AtUtc = (definition.Trigger.AtUtc ?? definition.Trigger.AnchorUtc)?.ToString("O") ?? "";
            Seconds = definition.Trigger.Seconds ?? 3600; Expression = definition.Trigger.Expression ?? "0 4 * * *"; TimeZoneId = definition.Trigger.TimeZoneId ?? "Asia/Tokyo";
            StepsJson = ControlJson.Serialize(definition.Template.Steps);
        }
        return Page();
    }

    public async Task<IActionResult> OnPostSaveAsync(Guid serverId)
    {
        await LoadAsync(serverId);
        try
        {
            if (!ModelState.IsValid) throw new ArgumentException("Invalid form.");
            var steps = ControlJson.Deserialize<ScheduleStep[]>(StepsJson);
            var trigger = TriggerKind switch
            {
                ScheduleTriggerKind.Once => new ScheduleTrigger { Kind = TriggerKind, AtUtc = ParseUtc(AtUtc) },
                ScheduleTriggerKind.Interval => new ScheduleTrigger { Kind = TriggerKind, Seconds = Seconds, AnchorUtc = ParseUtc(AtUtc) },
                ScheduleTriggerKind.Cron => new ScheduleTrigger { Kind = TriggerKind, Expression = Expression, TimeZoneId = TimeZoneId },
                _ => throw new ArgumentException("Invalid trigger.")
            };
            var definition = new ScheduleDefinition { ScheduleId = ScheduleId ?? Guid.NewGuid(), HostId = Server.HostId, ServerId = serverId,
                Revision = Revision.HasValue ? checked(Revision.Value + 1) : 1, Enabled = Enabled, Trigger = trigger,
                Template = new() { ActionType = steps.Length == 1 ? ScheduleActionType.Operation : ScheduleActionType.LocalChain, Steps = steps } };
            await store.SaveScheduleAsync(definition, Revision, RequestKey);
            await notifier.InventoryChangedAsync(Server.HostId);
            Message = "予約を保存しました。Agentの適用完了後に有効になります。";
            return RedirectToPage(new { serverId });
        }
        catch (Exception error) when (error is InventoryFault or ArgumentException or JsonException or OverflowException or TimeZoneNotFoundException or InvalidTimeZoneException)
        {
            ModelState.AddModelError("", error is InventoryFault fault ? fault.Code : "入力内容を確認してください。");
            return Page();
        }
    }

    public async Task<IActionResult> OnPostRunNowAsync(Guid serverId, Guid scheduleId)
    {
        await LoadAsync(serverId);
        if (!Schedules.Any(s => s.Definition.ScheduleId == scheduleId)) return NotFound();
        var prepared = false;
        try
        {
            var definition = await store.PrepareRunNowAsync(scheduleId, RequestKey);
            prepared = true;
            var receipt = await store.ManualRequestAsync(scheduleId, RequestKey);
            if (receipt.RejectionCode != null) { Message = "開始前に拒否されました: " + receipt.RejectionCode; return RedirectToPage(new { serverId }); }
            var report = await store.ManualReportAsync(scheduleId, RequestKey) ?? await agents.RunScheduleNowAsync(definition.HostId, scheduleId, definition.Revision, RequestKey, HttpContext.RequestAborted);
            await store.WriteAsync(async () => { await store.StageScheduleReportAsync(definition.HostId, report); return true; });
            Message = "実行を受け付けました。履歴で結果を確認してください。";
        }
        catch (InventoryFault fault)
        {
            Message = fault.Code;
            if (prepared && fault.Code is "StaleRevision" or "UnsupportedCapability" or "StateConflict" or "OperationConflict") await store.RejectManualRequestAsync(scheduleId, RequestKey, fault.Code);
        }
        catch (Exception error) when (error is IOException or OperationCanceledException or Microsoft.AspNetCore.SignalR.HubException)
        { Message = $"応答を確認できません。未確認の要求 {RequestKey} を同じIDで照合してください。"; }
        return RedirectToPage(new { serverId });
    }

    private static DateTimeOffset ParseUtc(string input)
    {
        if (!DateTimeOffset.TryParse(input, CultureInfo.InvariantCulture, DateTimeStyles.None, out var utc) || utc.Offset != TimeSpan.Zero || !input.EndsWith('Z') && !input.EndsWith("+00:00", StringComparison.Ordinal)) throw new ArgumentException("UTC with offset is required.");
        return utc;
    }
    private async Task LoadAsync(Guid serverId)
    {
        Server = await store.ServerAsync(serverId);
        Schedules = await store.SchedulesAsync(serverId);
        PendingRequests = await store.PendingManualRequestsAsync(serverId);
        foreach (var row in Schedules) History[row.Definition.ScheduleId] = await store.ScheduleHistoryAsync(row.Definition.ScheduleId);
    }
}
