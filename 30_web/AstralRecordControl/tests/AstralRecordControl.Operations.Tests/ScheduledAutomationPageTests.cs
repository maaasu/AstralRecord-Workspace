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
        page.Preset = ScheduleEditorPreset.DailyGeyserUpdate;
        page.DailyTime = "03:10";
        Assert.IsType<RedirectToPageResult>(await page.OnPostSaveAsync(f.ServerId));
        var saved = Assert.Single(await f.Store.SchedulesAsync(f.ServerId)).Definition;
        var step = Assert.Single(saved.Template.Steps);
        Assert.Equal(OperationKind.UpdateGeyser, step.Kind);
        Assert.True(FileOperationValidation.Read<UpdateGeyserPayload>(step.Payload).RestartIfPreviouslyRunning);
        var edit = f.Page();
        await edit.OnGetAsync(f.ServerId, saved.ScheduleId);
        Assert.Equal(ScheduleEditorPreset.DailyGeyserUpdate, edit.Preset);
        Assert.Equal("03:10", edit.DailyTime);
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
        page.StepsJson = ControlJson.Serialize(new[] { new ScheduleStep { StepKey = "update", Kind = OperationKind.UpdateGeyser, Payload = ControlJson.Element(new UpdateGeyserPayload()) } });
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
                Payload = kind == OperationKind.UpdateGeyser ? ControlJson.Element(new UpdateGeyserPayload()) : ControlJson.Element(new { }) }] } };
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
            f.Host.CapabilitiesJson = ControlJson.Serialize(capability ? [.. capabilities, "P3.GeyserUpdate"] : capabilities);
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
