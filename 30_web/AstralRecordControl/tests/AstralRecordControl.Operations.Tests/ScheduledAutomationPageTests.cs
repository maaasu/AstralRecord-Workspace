using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Hubs;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Pages.Servers;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace AstralRecordControl.Operations.Tests;

public sealed class ScheduledAutomationPageTests
{
    [Fact]
    public async Task DailyRestartIsScopedToServerAndReplayDoesNotDuplicateIt()
    {
        await using var f = await Fixture.CreateAsync();
        var second = await f.AddServerAsync("second", ServerKind.Java);
        var page = f.Page();
        await page.OnGetAsync(f.ServerId);
        page.DailyTime = "05:37";
        Assert.IsType<RedirectToPageResult>(await page.OnPostSaveAsync(f.ServerId));
        Assert.IsType<RedirectToPageResult>(await page.OnPostSaveAsync(f.ServerId));
        var saved = Assert.Single(await f.Store.SchedulesAsync(f.ServerId));
        Assert.Empty(await f.Store.SchedulesAsync(second));
        Assert.Null(saved.AppliedRevision);
        Assert.Equal("37 5 * * *", saved.Definition.Trigger.Expression);
        Assert.Equal("Asia/Tokyo", saved.Definition.Trigger.TimeZoneId);
        Assert.Equal(OperationKind.Restart, Assert.Single(saved.Definition.Template.Steps).Kind);
        Assert.Equal(f.ServerId, saved.Definition.ServerId);

        var edit = f.Page();
        await edit.OnGetAsync(f.ServerId, saved.Definition.ScheduleId);
        Assert.Equal(ScheduleEditorPreset.DailyRestart, edit.Preset);
        Assert.Equal("05:37", edit.DailyTime);
        edit.Enabled = false;
        edit.DailyTime = "06:15";
        Assert.IsType<RedirectToPageResult>(await edit.OnPostSaveAsync(f.ServerId));
        var disabled = Assert.Single(await f.Store.SchedulesAsync(f.ServerId)).Definition;
        Assert.False(disabled.Enabled);
        Assert.Equal(2, disabled.Revision);
        Assert.Equal("15 6 * * *", disabled.Trigger.Expression);
    }

    [Fact]
    public async Task VelocityPresetPersistsUpdatePayloadAndSupportsEditing()
    {
        await using var f = await Fixture.CreateAsync(ServerKind.Velocity);
        var page = f.Page();
        await page.OnGetAsync(f.ServerId);
        Assert.True(page.GeyserAvailable);
        Assert.True(page.GeyserDisplayEntityUpdateAvailable);
        Assert.True(page.UpdateGeyserDisplayEntity);
        Assert.True(page.ViaVersionUpdateAvailable);
        Assert.True(page.UpdateViaVersion);
        page.Preset = ScheduleEditorPreset.DailyProxyMaintenance;
        page.DailyTime = "03:10";
        Assert.IsType<RedirectToPageResult>(await page.OnPostSaveAsync(f.ServerId));
        var saved = Assert.Single(await f.Store.SchedulesAsync(f.ServerId)).Definition;
        var step = Assert.Single(saved.Template.Steps);
        Assert.Equal(OperationKind.UpdateGeyser, step.Kind);
        Assert.True(FileOperationValidation.Read<UpdateGeyserPayload>(step.Payload).RestartIfPreviouslyRunning);
        Assert.True(FileOperationValidation.Read<UpdateGeyserPayload>(step.Payload).UpdateVelocity);
        Assert.True(FileOperationValidation.Read<UpdateGeyserPayload>(step.Payload).RestartEvenIfUnchanged);
        Assert.True(FileOperationValidation.Read<UpdateGeyserPayload>(step.Payload).UpdateGeyserDisplayEntity);
        Assert.Contains("GeyserDisplayEntity", SchedulesModel.Description(saved));
        Assert.Contains("Velocity", SchedulesModel.Description(saved));
        Assert.True(FileOperationValidation.Read<UpdateGeyserPayload>(step.Payload).UpdateViaVersion);
        Assert.Contains("ViaVersion", SchedulesModel.Description(saved));
        var edit = f.Page();
        await edit.OnGetAsync(f.ServerId, saved.ScheduleId);
        Assert.Equal(ScheduleEditorPreset.DailyProxyMaintenance, edit.Preset);
        Assert.Equal("03:10", edit.DailyTime);
        Assert.True(edit.UpdateViaVersion);
        edit.UpdateViaVersion = false;
        Assert.IsType<RedirectToPageResult>(await edit.OnPostSaveAsync(f.ServerId));
        var edited = FileOperationValidation.Read<UpdateGeyserPayload>(Assert.Single(await f.Store.SchedulesAsync(f.ServerId)).Definition.Template.Steps[0].Payload);
        Assert.False(edited.UpdateViaVersion);
        Assert.True(edited.UpdateVelocity);
        Assert.True(edited.UpdateGeyserDisplayEntity);
        Assert.True(edited.RestartEvenIfUnchanged);
    }

    [Theory]
    [InlineData("{}")]
    [InlineData("{\"restartIfPreviouslyRunning\":true}")]
    [InlineData("{\"restartIfPreviouslyRunning\":false}")]
    [InlineData("{\"restartIfPreviouslyRunning\":false,\"updateVelocity\":true}")]
    [InlineData("{\"updateGeyserDisplayEntity\":false}")]
    [InlineData("{\"restartIfPreviouslyRunning\":false,\"updateGeyserDisplayEntity\":true}")]
    [InlineData("{\"updateViaVersion\":false}")]
    [InlineData("{\"restartIfPreviouslyRunning\":false,\"updateViaVersion\":true}")]
    public async Task EditingDailyGeyserPreservesLegacyAndExplicitPayloads(string json)
    {
        await using var f = await Fixture.CreateAsync(ServerKind.Velocity);
        var payload = ControlJson.Deserialize<System.Text.Json.JsonElement>(json);
        var definition = new ScheduleDefinition { ScheduleId = Guid.NewGuid(), HostId = f.Host.HostId, ServerId = f.ServerId, Revision = 1, Enabled = true,
            Trigger = new() { Kind = ScheduleTriggerKind.Cron, Expression = "0 4 * * *", TimeZoneId = "Asia/Tokyo" },
            Template = new() { Steps = [new() { StepKey = "update-geyser", Kind = OperationKind.UpdateGeyser, Payload = payload }] } };
        await f.Store.SaveScheduleAsync(definition, null, Guid.NewGuid());
        var page = f.Page();
        await page.OnGetAsync(f.ServerId, definition.ScheduleId);
        Assert.Equal(ScheduleEditorPreset.DailyGeyserUpdate, page.Preset);
        Assert.Equal(FileOperationValidation.Read<UpdateGeyserPayload>(payload).UpdateVelocity, page.UpdateVelocity);
        Assert.Equal(FileOperationValidation.Read<UpdateGeyserPayload>(payload).UpdateGeyserDisplayEntity, page.UpdateGeyserDisplayEntity);
        Assert.Equal(FileOperationValidation.Read<UpdateGeyserPayload>(payload).UpdateViaVersion, page.UpdateViaVersion);
        page.DailyTime = "05:15";
        Assert.IsType<RedirectToPageResult>(await page.OnPostSaveAsync(f.ServerId));
        var saved = Assert.Single(await f.Store.SchedulesAsync(f.ServerId)).Definition;
        Assert.Equal(payload.GetRawText(), saved.Template.Steps[0].Payload.GetRawText());
        page = f.Page();
        await page.OnGetAsync(f.ServerId, saved.ScheduleId);
        page.UpdateVelocity = !page.UpdateVelocity;
        Assert.IsType<RedirectToPageResult>(await page.OnPostSaveAsync(f.ServerId));
        var changed = FileOperationValidation.Read<UpdateGeyserPayload>(Assert.Single(await f.Store.SchedulesAsync(f.ServerId)).Definition.Template.Steps[0].Payload);
        Assert.Equal(FileOperationValidation.Read<UpdateGeyserPayload>(payload).RestartIfPreviouslyRunning, changed.RestartIfPreviouslyRunning);
        Assert.Equal(FileOperationValidation.Read<UpdateGeyserPayload>(payload).UpdateGeyserDisplayEntity, changed.UpdateGeyserDisplayEntity);
        Assert.Equal(FileOperationValidation.Read<UpdateGeyserPayload>(payload).UpdateViaVersion, changed.UpdateViaVersion);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ViaVersionUpdateRequiresItsOwnCapabilityAndCanBeDeselected(bool advanced)
    {
        await using var f = await Fixture.CreateAsync(ServerKind.Velocity);
        f.Host.CapabilitiesJson = ControlJson.Serialize(ControlJson.Deserialize<string[]>(f.Host.CapabilitiesJson)
            .Where(c => c != ServerProcessSupport.ViaVersionUpdateCapability).ToArray());
        await f.Db.SaveChangesAsync();
        var page = f.Page();
        await page.OnGetAsync(f.ServerId);
        Assert.True(page.GeyserDisplayEntityUpdateAvailable);
        Assert.True(page.VelocityUpdateAvailable);
        Assert.False(page.ViaVersionUpdateAvailable);
        var step = new ScheduleStep { StepKey = "update-geyser", Kind = OperationKind.UpdateGeyser,
            Payload = ControlJson.Element(new UpdateGeyserPayload(UpdateViaVersion: true)) };
        page.Preset = advanced ? ScheduleEditorPreset.Advanced : ScheduleEditorPreset.DailyProxyMaintenance;
        page.TriggerKind = ScheduleTriggerKind.Cron;
        page.StepsJson = ControlJson.Serialize(new[] { step });
        Assert.IsType<PageResult>(await page.OnPostSaveAsync(f.ServerId));
        Assert.Empty(await f.Store.SchedulesAsync(f.ServerId));

        // A host without the new capability still accepts the existing update payload.
        page = f.Page();
        await page.OnGetAsync(f.ServerId);
        page.UpdateViaVersion = false;
        Assert.IsType<RedirectToPageResult>(await page.OnPostSaveAsync(f.ServerId));
        var saved = Assert.Single(await f.Store.SchedulesAsync(f.ServerId)).Definition;
        Assert.False(FileOperationValidation.Read<UpdateGeyserPayload>(saved.Template.Steps[0].Payload).UpdateViaVersion);
        Assert.DoesNotContain("updateViaVersion", saved.Template.Steps[0].Payload.GetRawText());
        Assert.DoesNotContain("ViaVersion", SchedulesModel.Description(saved));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task CombinedUpdateRequiresNewCapabilityForPresetAndAdvanced(bool advanced)
    {
        await using var f = await Fixture.CreateAsync(ServerKind.Velocity);
        f.Host.CapabilitiesJson = ControlJson.Serialize(ControlJson.Deserialize<string[]>(f.Host.CapabilitiesJson).Where(c => c != ServerProcessSupport.VelocityUpdateCapability).ToArray());
        await f.Db.SaveChangesAsync();
        var page = f.Page();
        await page.OnGetAsync(f.ServerId);
        Assert.True(page.GeyserAvailable);
        Assert.False(page.VelocityUpdateAvailable);
        page.Preset = advanced ? ScheduleEditorPreset.Advanced : ScheduleEditorPreset.DailyProxyMaintenance;
        page.TriggerKind = ScheduleTriggerKind.Cron;
        page.StepsJson = ControlJson.Serialize(new[] { new ScheduleStep { StepKey = "update", Kind = OperationKind.UpdateGeyser, Payload = ControlJson.Element(new UpdateGeyserPayload(UpdateVelocity: true)) } });
        Assert.IsType<PageResult>(await page.OnPostSaveAsync(f.ServerId));
        Assert.Empty(await f.Store.SchedulesAsync(f.ServerId));
    }

    [Theory]
    [InlineData(ServerKind.Java, true, true)]
    [InlineData(ServerKind.Velocity, false, true)]
    [InlineData(ServerKind.Velocity, true, false)]
    public async Task UnsupportedGeyserSchedulesCannotBypassThePresetThroughAdvancedJson(ServerKind kind, bool files, bool capability)
    {
        await using var f = await Fixture.CreateAsync(kind, files, capability);
        var page = f.Page();
        await page.OnGetAsync(f.ServerId);
        page.Preset = ScheduleEditorPreset.Advanced;
        page.TriggerKind = ScheduleTriggerKind.Cron;
        page.StepsJson = ControlJson.Serialize(new[] { new ScheduleStep { StepKey = "update", Kind = OperationKind.UpdateGeyser, Payload = ControlJson.Element(new UpdateGeyserPayload(UpdateGeyserDisplayEntity: true)) } });
        Assert.IsType<PageResult>(await page.OnPostSaveAsync(f.ServerId));
        Assert.False(page.ModelState.IsValid);
        Assert.Empty(await f.Store.SchedulesAsync(f.ServerId));
    }

    [Theory]
    [InlineData("25:00", "Asia/Tokyo")]
    [InlineData("04:00", "Invalid/Timezone")]
    public async Task InvalidDailySettingsDoNotCreateDefinitions(string time, string zone)
    {
        await using var f = await Fixture.CreateAsync();
        var page = f.Page();
        await page.OnGetAsync(f.ServerId);
        page.DailyTime = time; page.TimeZoneId = zone;
        Assert.IsType<PageResult>(await page.OnPostSaveAsync(f.ServerId));
        Assert.False(page.ModelState.IsValid);
        Assert.Empty(await f.Store.SchedulesAsync(f.ServerId));
    }

    [Fact]
    public async Task ExistingFormWithoutPresetRetainsItsAdvancedOperation()
    {
        await using var f = await Fixture.CreateAsync();
        var page = f.Page();
        page.RequestKey = Guid.NewGuid();
        page.TriggerKind = ScheduleTriggerKind.Cron;
        page.Expression = "0 2 * * 1";
        page.StepsJson = ControlJson.Serialize(new[] { new ScheduleStep { StepKey = "stop", Kind = OperationKind.Stop } });
        Assert.IsType<RedirectToPageResult>(await page.OnPostSaveAsync(f.ServerId));
        var saved = Assert.Single(await f.Store.SchedulesAsync(f.ServerId)).Definition;
        Assert.Equal("0 2 * * 1", saved.Trigger.Expression);
        Assert.Equal(OperationKind.Stop, Assert.Single(saved.Template.Steps).Kind);
    }

    [Theory]
    [InlineData(OperationKind.Restart, "custom-restart", ScheduleActionType.Operation)]
    [InlineData(OperationKind.Restart, "restart", ScheduleActionType.LocalChain)]
    [InlineData(OperationKind.UpdateGeyser, "custom-update", ScheduleActionType.Operation)]
    [InlineData(OperationKind.UpdateGeyser, "update-geyser", ScheduleActionType.LocalChain)]
    public async Task EditingCustomDailyDefinitionPreservesItsStepKeyAndActionType(OperationKind kind, string stepKey, ScheduleActionType actionType)
    {
        await using var f = await Fixture.CreateAsync(ServerKind.Velocity);
        var definition = new ScheduleDefinition { ScheduleId = Guid.NewGuid(), HostId = f.Host.HostId, ServerId = f.ServerId, Revision = 1, Enabled = true,
            Trigger = new() { Kind = ScheduleTriggerKind.Cron, Expression = "0 4 * * *", TimeZoneId = "Asia/Tokyo" },
            Template = new() { ActionType = actionType, Steps = [new() { StepKey = stepKey, Kind = kind,
                Payload = kind == OperationKind.UpdateGeyser ? ControlJson.Element(new UpdateGeyserPayload(UpdateGeyserDisplayEntity: true)) : ControlJson.Element(new { }) }] } };
        await f.Store.SaveScheduleAsync(definition, null, Guid.NewGuid());
        var edit = f.Page();
        await edit.OnGetAsync(f.ServerId, definition.ScheduleId);
        Assert.Equal(ScheduleEditorPreset.Advanced, edit.Preset);
        edit.Enabled = false;
        Assert.IsType<RedirectToPageResult>(await edit.OnPostSaveAsync(f.ServerId));
        var saved = Assert.Single(await f.Store.SchedulesAsync(f.ServerId)).Definition;
        Assert.Equal(ControlJson.Serialize(definition.Template), ControlJson.Serialize(saved.Template));
        Assert.False(saved.Enabled);
    }

    [Fact]
    public async Task AdvancedChainIsPreservedWhenOpenedForEditing()
    {
        await using var f = await Fixture.CreateAsync();
        var page = f.Page();
        await page.OnGetAsync(f.ServerId);
        page.Preset = ScheduleEditorPreset.Advanced;
        page.TriggerKind = ScheduleTriggerKind.Cron;
        page.Expression = "0 3 * * 0";
        var steps = new[] {
            new ScheduleStep { StepKey = "announce", Kind = OperationKind.ConsoleCommand, Payload = ControlJson.Element(new { command = "say restart" }) },
            new ScheduleStep { StepKey = "restart", Kind = OperationKind.Restart, DelaySeconds = 60 }
        };
        page.StepsJson = ControlJson.Serialize(steps);
        Assert.IsType<RedirectToPageResult>(await page.OnPostSaveAsync(f.ServerId));
        var saved = Assert.Single(await f.Store.SchedulesAsync(f.ServerId)).Definition;
        var edit = f.Page();
        await edit.OnGetAsync(f.ServerId, saved.ScheduleId);
        Assert.Equal(ScheduleEditorPreset.Advanced, edit.Preset);
        Assert.Equal(page.StepsJson, edit.StepsJson);
        Assert.Equal("0 3 * * 0", edit.Expression);
    }

    [Fact]
    public async Task ProxyConsolidationIsOneAtomicInventoryVersionAndKeepsHistoryAndOtherDefinitions()
    {
        await using var f = await Fixture.CreateAsync(ServerKind.Velocity);
        var second = await f.AddServerAsync("other-proxy", ServerKind.Velocity);
        ScheduleDefinition Definition(OperationKind kind, Guid server, string time = "0 4 * * *", string zone = "Asia/Tokyo", string? key = null, ScheduleActionType action = ScheduleActionType.Operation) => new() {
            ScheduleId = Guid.NewGuid(), HostId = f.Host.HostId, ServerId = server, Revision = 1, Enabled = true,
            Trigger = new() { Kind = ScheduleTriggerKind.Cron, Expression = time, TimeZoneId = zone },
            Template = new() { ActionType = action, Steps = [new() { Kind = kind, StepKey = key ?? (kind == OperationKind.Restart ? "restart" : "update-geyser"),
                Payload = kind == OperationKind.UpdateGeyser ? ControlJson.Element(new UpdateGeyserPayload(false, UpdateGeyserDisplayEntity: true, UpdateViaVersion: true)) : ControlJson.Element(new { }) }] } };
        var restart = Definition(OperationKind.Restart, f.ServerId, "00 04 * * *");
        var update = Definition(OperationKind.UpdateGeyser, f.ServerId);
        var untouched = new[] { Definition(OperationKind.Restart, second), Definition(OperationKind.Restart, f.ServerId, "0 5 * * *"),
            Definition(OperationKind.Restart, f.ServerId, zone: "UTC"), Definition(OperationKind.Restart, f.ServerId, key: "custom"),
            Definition(OperationKind.UpdateGeyser, f.ServerId, action: ScheduleActionType.LocalChain),
            Definition(OperationKind.Restart, f.ServerId) with { Template = new() { Steps = [new() { Kind = OperationKind.Restart, StepKey = "restart", DelaySeconds = 30 }] } } };
        foreach (var definition in new[] { restart, update }.Concat(untouched))
            await f.Store.SaveScheduleAsync(definition, null, Guid.NewGuid());
        var historyId = Guid.NewGuid();
        var history = new ScheduleOccurrenceReport { ScheduleId = restart.ScheduleId, OperationId = historyId, Status = ScheduleOccurrenceStatus.Succeeded };
        var historyJson = ControlJson.Serialize(history);
        f.Db.ScheduleOccurrences.Add(new() { OperationId = historyId, HostId = f.Host.HostId, ScheduleId = restart.ScheduleId, ReportJson = historyJson, UpdatedAtUtc = DateTimeOffset.UtcNow });
        await f.Db.SaveChangesAsync();
        var before = f.Host.DesiredInventoryRevision;
        var page = f.Page();
        await page.OnGetAsync(f.ServerId);
        Assert.Equal(ScheduleEditorPreset.DailyProxyMaintenance, page.Preset);
        Assert.IsType<RedirectToPageResult>(await page.OnPostSaveAsync(f.ServerId));
        Assert.Equal(before + 1, f.Host.DesiredInventoryRevision);
        var all = await f.Store.SchedulesAsync();
        foreach (var old in new[] { restart, update })
        {
            var disabled = all.Single(d => d.Definition.ScheduleId == old.ScheduleId).Definition;
            Assert.False(disabled.Enabled);
            Assert.Equal(2, disabled.Revision);
        }
        foreach (var old in untouched)
            Assert.Equal(ControlJson.Serialize(old), ControlJson.Serialize(all.Single(d => d.Definition.ScheduleId == old.ScheduleId).Definition));
        Assert.Equal(historyJson, (await f.Db.ScheduleOccurrences.FindAsync(historyId))!.ReportJson);
        Assert.Single(await f.Store.ScheduleHistoryAsync(restart.ScheduleId));
        Assert.IsType<RedirectToPageResult>(await page.OnPostSaveAsync(f.ServerId));
        Assert.Equal(before + 1, f.Host.DesiredInventoryRevision);
        Assert.Equal(untouched.Length + 3, (await f.Store.SchedulesAsync()).Length);
    }

    [Fact]
    public async Task FailedProxySaveDoesNotDisableExistingSchedules()
    {
        await using var f = await Fixture.CreateAsync(ServerKind.Velocity);
        var page = f.Page();
        await page.OnGetAsync(f.ServerId);
        page.UpdateGeyser = false;
        Assert.IsType<RedirectToPageResult>(await page.OnPostSaveAsync(f.ServerId));
        var old = Assert.Single(await f.Store.SchedulesAsync(f.ServerId)).Definition;
        var before = f.Host.DesiredInventoryRevision;
        f.Host.CapabilitiesJson = ControlJson.Serialize(ControlJson.Deserialize<string[]>(f.Host.CapabilitiesJson).Where(c => c != ServerProcessSupport.ProxyMaintenanceCapability).ToArray());
        await f.Db.SaveChangesAsync();
        var failure = f.Page(); await failure.OnGetAsync(f.ServerId);
        Assert.IsType<PageResult>(await failure.OnPostSaveAsync(f.ServerId));
        Assert.Equal(before, f.Host.DesiredInventoryRevision);
        Assert.Equal(ControlJson.Serialize(old), ControlJson.Serialize(Assert.Single(await f.Store.SchedulesAsync(f.ServerId)).Definition));
        var edit = f.Page(); await edit.OnGetAsync(f.ServerId, old.ScheduleId);
        Assert.Equal(ScheduleEditorPreset.DailyProxyMaintenance, edit.Preset);
        Assert.False(edit.UpdateGeyser);
        Assert.False(edit.UpdateVelocity);
        Assert.False(edit.UpdateGeyserDisplayEntity);
        Assert.False(edit.UpdateViaVersion);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task ExtensionUpdateRequiresCapabilityInPresetAdvancedAndStore(bool advanced)
    {
        await using var f = await Fixture.CreateAsync(ServerKind.Velocity);
        f.Host.CapabilitiesJson = ControlJson.Serialize(ControlJson.Deserialize<string[]>(f.Host.CapabilitiesJson).Where(c => c != ServerProcessSupport.GeyserDisplayEntityUpdateCapability).ToArray());
        await f.Db.SaveChangesAsync();
        var page = f.Page();
        await page.OnGetAsync(f.ServerId);
        Assert.False(page.GeyserDisplayEntityUpdateAvailable);
        var step = new ScheduleStep { StepKey = "update-geyser", Kind = OperationKind.UpdateGeyser, Payload = ControlJson.Element(new UpdateGeyserPayload(UpdateGeyserDisplayEntity: true)) };
        page.Preset = advanced ? ScheduleEditorPreset.Advanced : ScheduleEditorPreset.DailyProxyMaintenance;
        page.TriggerKind = ScheduleTriggerKind.Cron;
        page.StepsJson = ControlJson.Serialize(new[] { step });
        Assert.IsType<PageResult>(await page.OnPostSaveAsync(f.ServerId));
        Assert.Empty(await f.Store.SchedulesAsync(f.ServerId));
        var definition = new ScheduleDefinition { ScheduleId = Guid.NewGuid(), HostId = f.Host.HostId, ServerId = f.ServerId, Revision = 1, Enabled = true,
            Trigger = new() { Kind = ScheduleTriggerKind.Cron, Expression = "0 4 * * *", TimeZoneId = "Asia/Tokyo" }, Template = new() { Steps = [step] } };
        var fault = await Assert.ThrowsAsync<InventoryFault>(() => f.Store.SaveScheduleAsync(definition, null, Guid.NewGuid()));
        Assert.Equal("UnsupportedCapability", fault.Code);
        // The parent switch makes both child update selections inapplicable.
        page = f.Page(); await page.OnGetAsync(f.ServerId);
        page.UpdateGeyser = false;
        Assert.True(page.UpdateGeyserDisplayEntity);
        Assert.IsType<RedirectToPageResult>(await page.OnPostSaveAsync(f.ServerId));
        var saved = Assert.Single(await f.Store.SchedulesAsync(f.ServerId)).Definition;
        Assert.Equal(OperationKind.Restart, Assert.Single(saved.Template.Steps).Kind);
        Assert.Equal("{}", saved.Template.Steps[0].Payload.GetRawText());
    }

    [Theory]
    [InlineData("{\"restartEvenIfUnchanged\":true}")]
    [InlineData("{\"restartEvenIfUnchanged\":true,\"updateGeyserDisplayEntity\":false}")]
    [InlineData("{\"restartEvenIfUnchanged\":true,\"updateGeyserDisplayEntity\":true,\"updateVelocity\":true}")]
    [InlineData("{\"restartIfPreviouslyRunning\":false}")]
    public async Task ExtensionSelectionRoundTripsAndCanBeChangedIndependently(string json)
    {
        await using var f = await Fixture.CreateAsync(ServerKind.Velocity);
        var payload = ControlJson.Deserialize<System.Text.Json.JsonElement>(json);
        var initial = FileOperationValidation.Read<UpdateGeyserPayload>(payload);
        var definition = new ScheduleDefinition { ScheduleId = Guid.NewGuid(), HostId = f.Host.HostId, ServerId = f.ServerId, Revision = 1, Enabled = true,
            Trigger = new() { Kind = ScheduleTriggerKind.Cron, Expression = "0 4 * * *", TimeZoneId = "Asia/Tokyo" },
            Template = new() { Steps = [new() { StepKey = "update-geyser", Kind = OperationKind.UpdateGeyser, Payload = payload }] } };
        await f.Store.SaveScheduleAsync(definition, null, Guid.NewGuid());
        var page = f.Page(); await page.OnGetAsync(f.ServerId, definition.ScheduleId);
        Assert.Equal(initial.UpdateGeyserDisplayEntity, page.UpdateGeyserDisplayEntity);
        page.DailyTime = "04:12";
        Assert.IsType<RedirectToPageResult>(await page.OnPostSaveAsync(f.ServerId));
        var saved = Assert.Single(await f.Store.SchedulesAsync(f.ServerId)).Definition;
        Assert.Equal(json, saved.Template.Steps[0].Payload.GetRawText());
        foreach (var selection in new[] { !initial.UpdateGeyserDisplayEntity, initial.UpdateGeyserDisplayEntity })
        {
            page = f.Page(); await page.OnGetAsync(f.ServerId, definition.ScheduleId);
            page.UpdateGeyserDisplayEntity = selection;
            Assert.IsType<RedirectToPageResult>(await page.OnPostSaveAsync(f.ServerId));
            saved = Assert.Single(await f.Store.SchedulesAsync(f.ServerId)).Definition;
            Assert.Equal(initial with { UpdateGeyserDisplayEntity = selection }, FileOperationValidation.Read<UpdateGeyserPayload>(saved.Template.Steps[0].Payload));
        }
    }

    [Fact]
    public async Task ExtensionFlagDoesNotTurnDelayedDailyStepIntoPreset()
    {
        await using var f = await Fixture.CreateAsync(ServerKind.Velocity);
        var definition = new ScheduleDefinition { ScheduleId = Guid.NewGuid(), HostId = f.Host.HostId, ServerId = f.ServerId, Revision = 1, Enabled = true,
            Trigger = new() { Kind = ScheduleTriggerKind.Cron, Expression = "0 4 * * *", TimeZoneId = "Asia/Tokyo" },
            Template = new() { Steps = [new() { StepKey = "update-geyser", Kind = OperationKind.UpdateGeyser, DelaySeconds = 30, Payload = ControlJson.Element(new UpdateGeyserPayload(false, UpdateGeyserDisplayEntity: true)) }] } };
        await f.Store.SaveScheduleAsync(definition, null, Guid.NewGuid());
        var page = f.Page(); await page.OnGetAsync(f.ServerId, definition.ScheduleId);
        Assert.Equal(ScheduleEditorPreset.Advanced, page.Preset);
        page.Enabled = false;
        Assert.IsType<RedirectToPageResult>(await page.OnPostSaveAsync(f.ServerId));
        Assert.Equal(ControlJson.Serialize(definition.Template), ControlJson.Serialize(Assert.Single(await f.Store.SchedulesAsync(f.ServerId)).Definition.Template));
    }

    [Theory]
    [InlineData(ScheduleTriggerKind.Once, "2026-11-02T04:00:00", "2026-11-01T19:00:00Z")]
    [InlineData(ScheduleTriggerKind.Interval, "2027-01-01T00:00:00.1234567", "2026-12-31T15:00:00.1234567Z")]
    public async Task JapanTimeFormStoresUtcAndEditingKeepsTheExactInstant(ScheduleTriggerKind kind, string local, string expected)
    {
        await using var f = await Fixture.CreateAsync();
        var page = f.Page(); page.RequestKey = Guid.NewGuid();
        page.Preset = ScheduleEditorPreset.Advanced; page.TriggerKind = kind; page.AtLocal = local;
        Assert.IsType<RedirectToPageResult>(await page.OnPostSaveAsync(f.ServerId));
        var first = Assert.Single(await f.Store.SchedulesAsync(f.ServerId)).Definition;
        var instant = first.Trigger.AtUtc ?? first.Trigger.AnchorUtc;
        Assert.Equal(DateTimeOffset.Parse(expected, System.Globalization.CultureInfo.InvariantCulture), instant);
        Assert.Equal(TimeSpan.Zero, instant!.Value.Offset);
        var edit = f.Page(); await edit.OnGetAsync(f.ServerId, first.ScheduleId);
        Assert.Equal(local, edit.AtLocal);
        Assert.IsType<RedirectToPageResult>(await edit.OnPostSaveAsync(f.ServerId));
        var edited = Assert.Single(await f.Store.SchedulesAsync(f.ServerId)).Definition;
        Assert.Equal(instant, edited.Trigger.AtUtc ?? edited.Trigger.AnchorUtc);
    }

    [Theory]
    [InlineData("2026-11-02T04:00:00Z", "")]
    [InlineData("2026-11-02T04:00:00+09:00", "")]
    [InlineData("2026-11-02T04:00:00", "2026-11-01T19:00:00Z")]
    public async Task LocalTimeFieldRejectsOffsetsAndConflictingLegacyUtc(string local, string utc)
    {
        await using var f = await Fixture.CreateAsync();
        var page = f.Page(); page.RequestKey = Guid.NewGuid();
        page.Preset = ScheduleEditorPreset.Advanced; page.TriggerKind = ScheduleTriggerKind.Once;
        page.AtLocal = local; page.AtUtc = utc;
        Assert.IsType<PageResult>(await page.OnPostSaveAsync(f.ServerId));
        Assert.False(page.ModelState.IsValid);
        Assert.Empty(await f.Store.SchedulesAsync(f.ServerId));
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly SqliteConnection connection = new("Data Source=:memory:");
        public ControlDbContext Db { get; private set; } = null!;
        public InventoryStore Store { get; private set; } = null!;
        public IConfiguration Settings { get; private set; } = null!;
        public InventoryHost Host { get; private set; } = null!;
        public Guid ServerId { get; private set; }
        private readonly Guid root = Guid.NewGuid(), executable = Guid.NewGuid();

        public static async Task<Fixture> CreateAsync(ServerKind kind = ServerKind.Java, bool files = true, bool capability = true)
        {
            var f = new Fixture(); await f.connection.OpenAsync();
            f.Db = new(new DbContextOptionsBuilder<ControlDbContext>().UseSqlite(f.connection).Options);
            await f.Db.Database.EnsureCreatedAsync();
            f.Settings = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> {
                ["Control:EnableProcessControl"] = "true", ["Control:EnableSchedules"] = "true", ["Control:EnableFileControl"] = files.ToString()
            }).Build();
            f.Store = new(f.Db, new InventoryWriteGate(), TimeProvider.System, f.Settings);
            f.Host = await f.Store.CreateHostAsync(new("fixture", "Fixture"), Guid.NewGuid(), "fixture");
            f.Host.ResourcesJson = ControlJson.Serialize(new HostResources { Roots = [new() { Id = f.root }], Executables = [new() { Id = f.executable }] });
            string[] capabilities = ["P0.Inventory", "P1.Operations", "P1.LegacyGate", "P1.Velocity", "P2.Files", "P3.Schedules"];
            f.Host.CapabilitiesJson = ControlJson.Serialize(capability ? [.. capabilities, "P3.GeyserUpdate", "P3.VelocityUpdate", "P3.ProxyMaintenance", "P3.GeyserDisplayEntityUpdate", "P3.ViaVersionUpdate"] : capabilities);
            await f.Db.SaveChangesAsync();
            f.ServerId = await f.AddServerAsync("server", kind);
            return f;
        }

        public async Task<Guid> AddServerAsync(string key, ServerKind kind)
        {
            var config = new ServerConfig { ServerId = Guid.NewGuid(), HostId = Host.HostId, Revision = 1, Kind = kind, ManagedRootId = root, ExecutableId = executable, ServerDirectory = key };
            var create = new ServerCreateRequest(Host.HostId, key, key, null, null, config);
            await Store.CreateServerAsync(create, Guid.NewGuid(), ControlJson.Serialize(create), ControlJson.Serialize(config));
            return config.ServerId;
        }

        public SchedulesModel Page() => new(Store, null!, new InventoryNotifier(null!, new NoopHub(), new AgentConnectionRegistry(), Store), Settings);
        public async ValueTask DisposeAsync() { await Db.DisposeAsync(); await connection.DisposeAsync(); }
    }

    private sealed class NoopHub : IHubContext<UiHub>
    {
        public IHubClients Clients { get; } = new NoopClients();
        public IGroupManager Groups => throw new NotSupportedException();
    }
    private sealed class NoopClients : IHubClients, IClientProxy
    {
        public IClientProxy All => this;
        public IClientProxy AllExcept(IReadOnlyList<string> ids) => this;
        public IClientProxy Client(string id) => this;
        public IClientProxy Clients(IReadOnlyList<string> ids) => this;
        public IClientProxy Group(string name) => this;
        public IClientProxy GroupExcept(string name, IReadOnlyList<string> ids) => this;
        public IClientProxy Groups(IReadOnlyList<string> names) => this;
        public IClientProxy User(string id) => this;
        public IClientProxy Users(IReadOnlyList<string> ids) => this;
        public Task SendCoreAsync(string method, object?[] args, CancellationToken token = default) => Task.CompletedTask;
    }
}
