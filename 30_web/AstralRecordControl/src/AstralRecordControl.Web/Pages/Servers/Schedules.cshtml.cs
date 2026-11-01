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

public enum ScheduleEditorPreset { DailyRestart, DailyGeyserUpdate, Advanced, DailyProxyMaintenance }

[Authorize(Policy = "OwnerOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class SchedulesModel(InventoryStore store, IAgentOperationTransport agents, InventoryNotifier notifier, IConfiguration configuration) : PageModel
{
    public InventoryServer Server { get; private set; } = null!;
    public ScheduleView[] Schedules { get; private set; } = [];
    public Dictionary<Guid, ScheduleOccurrenceReport[]> History { get; } = [];
    public ScheduleManualRequest[] PendingRequests { get; private set; } = [];
    public bool Available => configuration.GetValue<bool>("Control:EnableSchedules");
    public ServerKind Kind { get; private set; }
    public bool IsVelocity => Kind == ServerKind.Velocity;
    public bool GeyserAvailable { get; private set; }
    public bool ProxyMaintenanceAvailable { get; private set; }
    public bool EditingLegacyUpdate { get; private set; }
    [BindProperty] public bool UpdateGeyser { get; set; } = true;
    public bool VelocityUpdateAvailable { get; private set; }
    [BindProperty] public bool UpdateVelocity { get; set; } = true;
    [BindProperty] public ScheduleEditorPreset Preset { get; set; } = ScheduleEditorPreset.Advanced;
    [BindProperty] public string? DailyTime { get; set; } = "04:00";
    [BindProperty] public Guid RequestKey { get; set; }
    [BindProperty] public Guid? ScheduleId { get; set; }
    [BindProperty] public long? Revision { get; set; }
    [BindProperty] public bool Enabled { get; set; } = true;
    [BindProperty] public ScheduleTriggerKind TriggerKind { get; set; }
    [BindProperty] public string? AtUtc { get; set; } = "";
    [BindProperty] public int Seconds { get; set; } = 3600;
    [BindProperty] public string? Expression { get; set; } = "0 4 * * *";
    [BindProperty] public string? TimeZoneId { get; set; } = "Asia/Tokyo";
    [BindProperty] public string? StepsJson { get; set; } = "[{\"stepKey\":\"restart\",\"kind\":\"Restart\",\"payload\":{},\"delaySeconds\":0}]";
    [TempData] public string? Message { get; set; }

    public async Task<IActionResult> OnGetAsync(Guid serverId, Guid? edit = null)
    {
        await LoadAsync(serverId);
        RequestKey = Guid.NewGuid();
        Preset = IsVelocity ? ScheduleEditorPreset.DailyProxyMaintenance : ScheduleEditorPreset.DailyRestart;
        if (edit.HasValue)
        {
            var definition = Schedules.SingleOrDefault(s => s.Definition.ScheduleId == edit)?.Definition;
            if (definition == null) return NotFound();
            ScheduleId = definition.ScheduleId; Revision = definition.Revision; Enabled = definition.Enabled;
            TriggerKind = definition.Trigger.Kind; AtUtc = (definition.Trigger.AtUtc ?? definition.Trigger.AnchorUtc)?.ToString("O") ?? "";
            Seconds = definition.Trigger.Seconds ?? 3600; Expression = definition.Trigger.Expression ?? "0 4 * * *"; TimeZoneId = definition.Trigger.TimeZoneId ?? "Asia/Tokyo";
            StepsJson = ControlJson.Serialize(definition.Template.Steps);
            Preset = ScheduleEditorPreset.Advanced;
            if (TryDailyPreset(definition, out var preset, out var dailyTime))
            {
                Preset = preset; DailyTime = dailyTime;
                if (preset is ScheduleEditorPreset.DailyGeyserUpdate or ScheduleEditorPreset.DailyProxyMaintenance)
                {
                    UpdateVelocity = FileOperationValidation.Read<UpdateGeyserPayload>(definition.Template.Steps[0].Payload).UpdateVelocity;
                    EditingLegacyUpdate = preset == ScheduleEditorPreset.DailyGeyserUpdate;
                }
                else if (IsVelocity && preset == ScheduleEditorPreset.DailyRestart)
                { Preset = ScheduleEditorPreset.DailyProxyMaintenance; UpdateGeyser = false; UpdateVelocity = false; }
            }
        }
        return Page();
    }

    public async Task<IActionResult> OnPostSaveAsync(Guid serverId)
    {
        await LoadAsync(serverId);
        try
        {
            if (!ModelState.IsValid) throw new ArgumentException("Invalid form.");
            if (!Enum.IsDefined(Preset)) throw new ArgumentException("Invalid preset.");
            var original = Schedules.SingleOrDefault(s => s.Definition.ScheduleId == ScheduleId)?.Definition;
            EditingLegacyUpdate = original != null && TryDailyPreset(original, out var oldPreset, out _) && oldPreset == ScheduleEditorPreset.DailyGeyserUpdate;
            if (Preset == ScheduleEditorPreset.DailyGeyserUpdate && !EditingLegacyUpdate) throw new ArgumentException("Legacy preset is edit-only.");
            ScheduleStep[] steps;
            ScheduleTrigger trigger;
            if (Preset != ScheduleEditorPreset.Advanced)
            {
                if (!ServerProcessSupport.IsSupported(Kind)) throw new InventoryFault(409, "UnsupportedCapability");
                if (!TimeOnly.TryParseExact(DailyTime, "HH:mm", CultureInfo.InvariantCulture, DateTimeStyles.None, out var time))
                    throw new ArgumentException("Invalid daily time.");
                if (Preset == ScheduleEditorPreset.DailyProxyMaintenance && !IsVelocity)
                    throw new ArgumentException("Invalid proxy update selection.");
                if (Preset == ScheduleEditorPreset.DailyProxyMaintenance && UpdateGeyser && (!ProxyMaintenanceAvailable || UpdateVelocity && !VelocityUpdateAvailable))
                    throw new InventoryFault(409, "UnsupportedCapability");
                if (Preset == ScheduleEditorPreset.DailyGeyserUpdate && (!IsVelocity || !GeyserAvailable || UpdateVelocity && !VelocityUpdateAvailable))
                    throw new InventoryFault(409, "UnsupportedCapability");
                trigger = new() { Kind = ScheduleTriggerKind.Cron, Expression = FormattableString.Invariant($"{time.Minute} {time.Hour} * * *"), TimeZoneId = TimeZoneId };
                steps = Preset == ScheduleEditorPreset.DailyRestart || Preset == ScheduleEditorPreset.DailyProxyMaintenance && !UpdateGeyser
                    ? [new() { StepKey = "restart", Kind = OperationKind.Restart }]
                    : [new() { StepKey = "update-geyser", Kind = OperationKind.UpdateGeyser, Payload = GeyserPresetPayload() }];
            }
            else
            {
                steps = ControlJson.Deserialize<ScheduleStep[]>(StepsJson ?? "");
                trigger = TriggerKind switch
                {
                    ScheduleTriggerKind.Once => new ScheduleTrigger { Kind = TriggerKind, AtUtc = ParseUtc(AtUtc) },
                    ScheduleTriggerKind.Interval => new ScheduleTrigger { Kind = TriggerKind, Seconds = Seconds, AnchorUtc = ParseUtc(AtUtc) },
                    ScheduleTriggerKind.Cron => new ScheduleTrigger { Kind = TriggerKind, Expression = Expression, TimeZoneId = TimeZoneId },
                    _ => throw new ArgumentException("Invalid trigger.")
                };
            }
            var actionType = steps.Length == 1 ? ScheduleActionType.Operation : ScheduleActionType.LocalChain;
            if (Preset == ScheduleEditorPreset.Advanced && Schedules.SingleOrDefault(s => s.Definition.ScheduleId == ScheduleId)?.Definition.Template.ActionType == ScheduleActionType.LocalChain)
                actionType = ScheduleActionType.LocalChain;
            var definition = new ScheduleDefinition { ScheduleId = ScheduleId ?? ScheduleIdentity.Uuid5(serverId, "schedule:" + RequestKey.ToString("D")), HostId = Server.HostId, ServerId = serverId,
                Revision = Revision.HasValue ? checked(Revision.Value + 1) : 1, Enabled = Enabled, Trigger = trigger,
                Template = new() { ActionType = actionType, Steps = steps } };
            await store.SaveScheduleAsync(definition, Revision, RequestKey, consolidateDailyProxySchedules: Preset == ScheduleEditorPreset.DailyProxyMaintenance);
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

    private JsonElement GeyserPresetPayload()
    {
        if (Preset == ScheduleEditorPreset.DailyProxyMaintenance)
            return ControlJson.Element(new UpdateGeyserPayload(UpdateVelocity: UpdateVelocity, RestartEvenIfUnchanged: true));
        var old = Schedules.SingleOrDefault(s => s.Definition.ScheduleId == ScheduleId)?.Definition;
        if (old != null && TryDailyPreset(old, out var preset, out _) && preset == ScheduleEditorPreset.DailyGeyserUpdate)
        {
            var payload = old.Template.Steps[0].Payload;
            var value = FileOperationValidation.Read<UpdateGeyserPayload>(payload);
            // Keep omitted legacy defaults and restart policy when changing only the time.
            if (value.UpdateVelocity == UpdateVelocity) return payload;
            return ControlJson.Element(value with { UpdateVelocity = UpdateVelocity });
        }
        return ControlJson.Element(new UpdateGeyserPayload(UpdateVelocity: UpdateVelocity));
    }

    public static string Description(ScheduleDefinition definition)
    {
        if (!TryDailyPreset(definition, out var preset, out var time)) return $"予約実行 — {definition.Trigger.Kind}";
        var label = "定期再起動";
        if (preset != ScheduleEditorPreset.DailyRestart)
        {
            var payload = FileOperationValidation.Read<UpdateGeyserPayload>(definition.Template.Steps[0].Payload);
            label = payload.RestartEvenIfUnchanged ? "定期再起動・GeyserMC 更新" : "更新のみ（旧予約）・GeyserMC";
            if (payload.UpdateVelocity) label += "・Velocity";
        }
        return $"{label} — 毎日 {time}（{definition.Trigger.TimeZoneId}）";
    }

    private static bool TryDailyPreset(ScheduleDefinition definition, out ScheduleEditorPreset preset, out string time)
    {
        preset = ScheduleEditorPreset.Advanced; time = "04:00";
        var fields = definition.Trigger.Expression?.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (definition.Trigger.Kind != ScheduleTriggerKind.Cron || definition.Trigger.AtUtc.HasValue || definition.Trigger.AnchorUtc.HasValue || definition.Trigger.Seconds.HasValue || fields is not { Length: 5 } ||
            fields[2] != "*" || fields[3] != "*" || fields[4] != "*" ||
            !int.TryParse(fields[0], NumberStyles.None, CultureInfo.InvariantCulture, out var minute) || minute is < 0 or > 59 ||
            !int.TryParse(fields[1], NumberStyles.None, CultureInfo.InvariantCulture, out var hour) || hour is < 0 or > 23 ||
            definition.Template.ActionType != ScheduleActionType.Operation ||
            definition.Template.Steps is not { Length: 1 } steps || steps[0].DelaySeconds != 0) return false;
        var step = steps[0];
        if (step.Kind == OperationKind.Restart && step.StepKey == "restart" && step.Payload.ValueKind == JsonValueKind.Object && !step.Payload.EnumerateObject().Any())
            preset = ScheduleEditorPreset.DailyRestart;
        else if (step.Kind == OperationKind.UpdateGeyser && step.StepKey == "update-geyser" && step.Payload.ValueKind == JsonValueKind.Object &&
            step.Payload.EnumerateObject().All(p => p.Name is "restartIfPreviouslyRunning" or "updateVelocity" or "restartEvenIfUnchanged" && p.Value.ValueKind is JsonValueKind.True or JsonValueKind.False))
            preset = FileOperationValidation.Read<UpdateGeyserPayload>(step.Payload).RestartEvenIfUnchanged ? ScheduleEditorPreset.DailyProxyMaintenance : ScheduleEditorPreset.DailyGeyserUpdate;
        else return false;
        time = new TimeOnly(hour, minute).ToString("HH:mm", CultureInfo.InvariantCulture);
        return true;
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

    private static DateTimeOffset ParseUtc(string? input)
    {
        if (input is null || !DateTimeOffset.TryParse(input, CultureInfo.InvariantCulture, DateTimeStyles.None, out var utc) || utc.Offset != TimeSpan.Zero || !input.EndsWith('Z') && !input.EndsWith("+00:00", StringComparison.Ordinal)) throw new ArgumentException("UTC with offset is required.");
        return utc;
    }
    private async Task LoadAsync(Guid serverId)
    {
        Server = await store.ServerAsync(serverId);
        Kind = ControlJson.Deserialize<ServerConfig>(Server.DesiredConfigJson).Kind;
        var host = await store.HostAsync(Server.HostId);
        var capabilities = ControlJson.Deserialize<string[]>(host.CapabilitiesJson);
        GeyserAvailable = IsVelocity && configuration.GetValue<bool>("Control:EnableFileControl") &&
            capabilities.Contains("P2.Files") && ServerProcessSupport.HasGeyserUpdateCapabilities(new UpdateGeyserPayload(), capabilities);
        VelocityUpdateAvailable = GeyserAvailable && ServerProcessSupport.HasGeyserUpdateCapabilities(new UpdateGeyserPayload(UpdateVelocity: true), capabilities);
        ProxyMaintenanceAvailable = GeyserAvailable && ServerProcessSupport.HasGeyserUpdateCapabilities(new UpdateGeyserPayload(RestartEvenIfUnchanged: true), capabilities);
        Schedules = await store.SchedulesAsync(serverId);
        PendingRequests = await store.PendingManualRequestsAsync(serverId);
        foreach (var row in Schedules) History[row.Definition.ScheduleId] = await store.ScheduleHistoryAsync(row.Definition.ScheduleId);
    }
}
