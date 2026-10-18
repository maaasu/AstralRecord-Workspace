using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Operations;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace AstralRecordControl.Operations.Tests;

public sealed class ScheduleProjectionTests
{
    [Fact]
    public async Task SchedulesRequireAppliedRevisionAndSurviveOtherInventoryChanges()
    {
        await using var f = await Fixture.CreateAsync();
        var key = Guid.NewGuid(); await f.Store.SaveScheduleAsync(f.Schedule, null, key);
        await f.Store.SaveScheduleAsync(f.Schedule, null, key);
        Assert.Single(await f.Store.SchedulesAsync());
        await Fault("StaleRevision", () => f.Store.AppliedScheduleAsync(f.Schedule.ScheduleId));
        f.Host.AppliedInventoryRevision = f.Host.DesiredInventoryRevision; await f.Db.SaveChangesAsync();
        Assert.Equal(1, (await f.Store.AppliedScheduleAsync(f.Schedule.ScheduleId)).Revision);
        var update = new ServerUpdateRequest("new", null, null, f.Config with { Revision = 2 });
        await f.Store.UpdateServerAsync(f.Config.ServerId, update, 1, ControlJson.Serialize(update.Config));
        Assert.Single(await f.Store.SchedulesAsync());
        await f.Store.SaveScheduleAsync(f.Schedule with { Revision = 2, Enabled = false }, 1, Guid.NewGuid());
        await Fault("StaleRevision", () => f.Store.AppliedScheduleAsync(f.Schedule.ScheduleId));
    }

    [Fact]
    public async Task HistoricalOfflineOccurrenceIsAcceptedButSpoofedIdentityAndFutureTriggerAreRejected()
    {
        await using var f = await Fixture.CreateAsync();
        await f.Store.SaveScheduleAsync(f.Schedule, null, Guid.NewGuid());
        f.Host.AppliedInventoryRevision = f.Host.DesiredInventoryRevision; await f.Db.SaveChangesAsync();
        var report = f.Report();
        await f.Store.SaveScheduleAsync(f.Schedule with { Revision = 2, Enabled = false }, 1, Guid.NewGuid());
        await f.Stage(report); await f.Stage(report);
        Assert.Single(await f.Store.ScheduleHistoryAsync(f.Schedule.ScheduleId));
        await Fault("InvalidEvent", () => f.Stage(report with { HostId = Guid.NewGuid() }));
        await Fault("OperationConflict", () => f.Stage(report with { OperationId = Guid.NewGuid() }));
        var falseTime = report.ScheduledAtUtc.AddSeconds(1);
        var falseId = ScheduleIdentity.Occurrence(f.Schedule.ScheduleId, 1, falseTime);
        await Fault("OperationConflict", () => f.Stage(report with { ScheduledAtUtc = falseTime, OperationId = falseId, WorkflowId = ScheduleIdentity.Uuid5(falseId, "workflow"), ReservationId = ScheduleIdentity.Uuid5(falseId, "reservation") }));
    }

    [Fact]
    public async Task OfflineManualRequestsAreRejectedBeforePersistenceAndDefiniteAgentRejectionIsRecorded()
    {
        await using var f = await Fixture.CreateAsync();
        await f.Store.SaveScheduleAsync(f.Schedule, null, Guid.NewGuid());
        f.Host.AppliedInventoryRevision = f.Host.DesiredInventoryRevision; f.Host.ConnectionState = HostConnectionState.Offline; await f.Db.SaveChangesAsync();
        var id = Guid.NewGuid(); await Fault("HostOffline", () => f.Store.PrepareRunNowAsync(f.Schedule.ScheduleId, id));
        Assert.Null(await f.Db.ScheduleManualRequests.FindAsync(id));
        (await f.Store.HostAsync(f.Host.HostId)).ConnectionState = HostConnectionState.Online; await f.Db.SaveChangesAsync();
        await f.Store.PrepareRunNowAsync(f.Schedule.ScheduleId, id);
        await f.Store.RejectManualRequestAsync(f.Schedule.ScheduleId, id, "StaleRevision");
        Assert.Equal("StaleRevision", (await f.Store.ManualRequestAsync(f.Schedule.ScheduleId, id)).RejectionCode);
        Assert.Equal(1, (await f.Store.PrepareRunNowAsync(f.Schedule.ScheduleId, id)).Revision);
    }

    [Fact]
    public async Task ManualOccurrenceRequiresMatchingPersistedOwnerRequest()
    {
        await using var f = await Fixture.CreateAsync();
        await f.Store.SaveScheduleAsync(f.Schedule, null, Guid.NewGuid());
        f.Host.AppliedInventoryRevision = f.Host.DesiredInventoryRevision; await f.Db.SaveChangesAsync();
        var id = Guid.NewGuid(); var report = f.Report() with { Manual = true, OperationId = id, WorkflowId = ScheduleIdentity.Uuid5(id, "workflow"), ReservationId = ScheduleIdentity.Uuid5(id, "reservation") };
        await Fault("OperationConflict", () => f.Stage(report));
        await f.Store.PrepareRunNowAsync(f.Schedule.ScheduleId, id);
        await f.Stage(report);
        Assert.True(Assert.Single(await f.Store.ScheduleHistoryAsync(f.Schedule.ScheduleId)).Manual);
        await f.Store.SaveScheduleAsync(f.Schedule with { Revision = 2, Enabled = false }, 1, Guid.NewGuid());
        Assert.Equal(1, (await f.Store.PrepareRunNowAsync(f.Schedule.ScheduleId, id)).Revision);
        await Fault("OperationConflict", () => f.Store.PrepareRunNowAsync(Guid.NewGuid(), id));
    }

    private static async Task Fault(string code, Func<Task> action) => Assert.Equal(code, (await Assert.ThrowsAsync<InventoryFault>(action)).Code);
    [Fact]
    public async Task HistoryRetentionRetiresTerminalPayloadAndPreservesRecoveryAndIdempotency()
    {
        await using var f = await Fixture.CreateAsync();
        var old = DateTimeOffset.UtcNow.AddDays(-91);
        var workflow = new ControlWorkflow { WorkflowId = Guid.NewGuid(), Status = WorkflowStatus.Succeeded, CreatedAtUtc = old, UpdatedAtUtc = old, RequestJson = "old payload" };
        var lease = new ControlReservationLease { ReservationId = Guid.NewGuid(), WorkflowId = workflow.WorkflowId, Status = ReservationStatus.Released, CreatedAtUtc = old };
        var operation = new ControlOperation { OperationId = Guid.NewGuid(), IdempotencyKey = Guid.NewGuid(), HostId = f.Host.HostId, ServerId = f.Config.ServerId, WorkflowId = workflow.WorkflowId, ReservationId = lease.ReservationId,
            CommandJson = "old command", CommandSha256 = ControlProtocol.Hash("old command"), RequestSha256 = ControlProtocol.Hash("old request"), ResultJson = "old result", Status = OperationStatus.Succeeded, FinishedAtUtc = old, CreatedAtUtc = old };
        f.Db.Workflows.Add(workflow); f.Db.ReservationLeases.Add(lease); f.Db.Operations.Add(operation);
        var report = f.Report() with { UpdatedAtUtc = old, Status = ScheduleOccurrenceStatus.Succeeded };
        f.Host.AckAgentEventSeq = 10;
        f.Db.ScheduleOccurrences.Add(new() { OperationId = report.OperationId, HostId = report.HostId, ScheduleId = report.ScheduleId, UpdatedAtUtc = old, ReportJson = ControlJson.Serialize(report), AckedAgentEventSeq = 10 });
        var unacked = report with { OperationId = Guid.NewGuid() };
        f.Db.ScheduleOccurrences.Add(new() { OperationId = unacked.OperationId, HostId = unacked.HostId, ScheduleId = unacked.ScheduleId, UpdatedAtUtc = old, ReportJson = ControlJson.Serialize(unacked) });
        var recovery = report with { OperationId = Guid.NewGuid(), Status = ScheduleOccurrenceStatus.NeedsAttention };
        f.Db.ScheduleOccurrences.Add(new() { OperationId = recovery.OperationId, HostId = recovery.HostId, ScheduleId = recovery.ScheduleId, UpdatedAtUtc = old, ReportJson = ControlJson.Serialize(recovery) });
        await f.Db.SaveChangesAsync();
        var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string,string?> { ["Control:DataRoot"] = Path.Combine(Path.GetTempPath(), "control-retention-" + Guid.NewGuid().ToString("N")) }).Build();
        await new ControlHistoryRetention(f.Db, f.Store, new OperationResultStore(config, f.Db), TimeProvider.System).RunAsync();
        Assert.Equal("", operation.CommandJson); Assert.Null(operation.ResultJson); Assert.Equal(ControlProtocol.Hash("old request"), operation.RequestSha256);
        Assert.Equal("", (await f.Db.ScheduleOccurrences.FindAsync(report.OperationId))!.ReportJson);
        Assert.NotEqual("", (await f.Db.ScheduleOccurrences.FindAsync(recovery.OperationId))!.ReportJson);
        Assert.NotEqual("", (await f.Db.ScheduleOccurrences.FindAsync(unacked.OperationId))!.ReportJson);
    }
    private sealed class Fixture : IAsyncDisposable
    {
        private readonly SqliteConnection connection = new("Data Source=:memory:");
        public ControlDbContext Db { get; private set; } = null!;
        public InventoryStore Store { get; private set; } = null!;
        public InventoryHost Host { get; private set; } = null!;
        public ServerConfig Config { get; private set; } = null!;
        public ScheduleDefinition Schedule { get; private set; } = null!;
        public static async Task<Fixture> CreateAsync()
        {
            var f = new Fixture(); await f.connection.OpenAsync();
            f.Db = new(new DbContextOptionsBuilder<ControlDbContext>().UseSqlite(f.connection).Options); await f.Db.Database.EnsureCreatedAsync();
            var settings = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string,string?> { ["Control:EnableProcessControl"] = "true", ["Control:EnableSchedules"] = "true" }).Build();
            f.Store = new(f.Db, new InventoryWriteGate(), TimeProvider.System, settings);
            f.Host = await f.Store.CreateHostAsync(new("schedule-pc", "Schedule"), Guid.NewGuid(), "schedule");
            var root = Guid.NewGuid(); var executable = Guid.NewGuid();
            f.Host.ResourcesJson = ControlJson.Serialize(new HostResources { Roots = [new() { Id = root }], Executables = [new() { Id = executable }] });
            f.Host.CapabilitiesJson = ControlJson.Serialize(new[] { "P0.Inventory", "P1.Operations", "P1.LegacyGate", "P3.Schedules" }); await f.Db.SaveChangesAsync();
            f.Host.ConnectionState = HostConnectionState.Online; f.Host.LastSeenAtUtc = DateTimeOffset.UtcNow; f.Host.ConnectionEpoch = Guid.NewGuid(); await f.Db.SaveChangesAsync();
            f.Config = new() { ServerId = Guid.NewGuid(), HostId = f.Host.HostId, Revision = 1, ManagedRootId = root, ExecutableId = executable, ServerDirectory = "dev" };
            var create = new ServerCreateRequest(f.Host.HostId, "dev", "Dev", null, null, f.Config);
            await f.Store.CreateServerAsync(create, Guid.NewGuid(), ControlJson.Serialize(create), ControlJson.Serialize(f.Config));
            f.Schedule = new() { ScheduleId = Guid.NewGuid(), HostId = f.Host.HostId, ServerId = f.Config.ServerId, Revision = 1, Enabled = true,
                Trigger = new() { Kind = ScheduleTriggerKind.Interval, Seconds = 60, AnchorUtc = DateTimeOffset.UtcNow.AddMinutes(-10) },
                Template = new() { ActionType = ScheduleActionType.Operation, Steps = [new() { StepKey = "restart", Kind = OperationKind.Restart }] } };
            return f;
        }
        public ScheduleOccurrenceReport Report()
        {
            var at = Schedule.Trigger.AnchorUtc!.Value; var id = ScheduleIdentity.Occurrence(Schedule.ScheduleId, 1, at);
            return new() { ScheduleId = Schedule.ScheduleId, HostId = Host.HostId, ServerId = Config.ServerId, Revision = 1, ScheduledAtUtc = at,
                OperationId = id, WorkflowId = ScheduleIdentity.Uuid5(id, "workflow"), ReservationId = ScheduleIdentity.Uuid5(id, "reservation"),
                AppliedInventoryRevision = Host.AppliedInventoryRevision, DefinitionSha256 = ControlProtocol.Hash(ControlJson.Serialize(Schedule)), Status = ScheduleOccurrenceStatus.Skipped, UpdatedAtUtc = DateTimeOffset.UtcNow };
        }
        public Task Stage(ScheduleOccurrenceReport report) => Store.WriteAsync(async () => { await Store.StageScheduleReportAsync(Host.HostId, report); return true; });
        public async ValueTask DisposeAsync() { await Db.DisposeAsync(); await connection.DisposeAsync(); }
    }
}
