using AstralRecordControl.Agent;
using AstralRecordControl.Agent.Storage;
using AstralRecordControl.Contracts;
using Xunit;

namespace AstralRecordControl.Agent.Tests;

public sealed class ScheduleExecutionTests
{
    [Fact]
    public async Task OnlyAcknowledgedSnapshotExecutesAndSameOccurrenceSurvivesRestart()
    {
        using var fixture = new Fixture();
        var at = fixture.Clock.GetUtcNow();
        var schedule = fixture.Schedule(new ScheduleTrigger { Kind = ScheduleTriggerKind.Once, AtUtc = at });
        using (var store = fixture.OpenStore())
        {
            fixture.Apply(store, 1, schedule);
            await fixture.Service(store).TickAsync();
            Assert.Empty(store.ReadScheduleReports());
            Assert.Empty(fixture.Runner.Executions);
            store.MarkScheduleInventoryAcknowledged(1);
            Assert.Single(store.GetAcknowledgedScheduleDefinitions().Definitions);
            Assert.Single(AgentSchedulePlanner.DueBetween(schedule, AgentSchedulePlanner.InitialCursor(schedule, fixture.Clock.GetUtcNow()), fixture.Clock.GetUtcNow()));
            await fixture.Service(store).TickAsync();
            Assert.True(fixture.Runner.Executions.Count == 1, ControlJson.Serialize(store.ReadScheduleReports().Select(x => x.Report).ToArray()));
            var report = Assert.Single(store.ReadScheduleReports()).Report;
            Assert.Equal(ScheduleIdentity.Occurrence(schedule.ScheduleId, schedule.Revision, at), report.OperationId);
            Assert.Equal(ScheduleOccurrenceStatus.Succeeded, report.Status);
            Assert.Equal(ScheduleIdentity.Child(report.WorkflowId, "first", 1), Assert.Single(report.Steps).OperationId);
        }
        using (var restarted = fixture.OpenStore())
        {
            await fixture.Service(restarted).TickAsync();
            Assert.Single(fixture.Runner.Executions);
            Assert.Single(restarted.ReadScheduleReports());
        }
    }

    [Fact]
    public async Task MissedOccurrenceIsSkippedAndNewRevisionHasDifferentIdentity()
    {
        using var fixture = new Fixture();
        var at = fixture.Clock.GetUtcNow().AddMinutes(-2);
        var original = fixture.Schedule(new ScheduleTrigger { Kind = ScheduleTriggerKind.Once, AtUtc = at });
        using var store = fixture.OpenStore();
        fixture.Apply(store, 1, original); store.MarkScheduleInventoryAcknowledged(1);
        await fixture.Service(store).TickAsync();
        Assert.Equal(ScheduleOccurrenceStatus.Skipped, Assert.Single(store.ReadScheduleReports()).Report.Status);
        Assert.Empty(fixture.Runner.Executions);
        var revised = original with { Revision = 2, Trigger = new ScheduleTrigger { Kind = ScheduleTriggerKind.Once, AtUtc = fixture.Clock.GetUtcNow() } };
        fixture.Apply(store, 2, revised); store.MarkScheduleInventoryAcknowledged(2);
        await fixture.Service(store).TickAsync();
        var reports = store.ReadScheduleReports();
        Assert.Equal(2, reports.Length);
        Assert.NotEqual(reports[0].Report.OperationId, reports[1].Report.OperationId);
        Assert.Single(fixture.Runner.Executions);
    }

    [Fact]
    public async Task UnacknowledgedInventoryChangeDoesNotStartPreviouslyClaimedOccurrence()
    {
        using var fixture = new Fixture();
        var at = fixture.Clock.GetUtcNow();
        var original = fixture.Schedule(new ScheduleTrigger { Kind = ScheduleTriggerKind.Once, AtUtc = at });
        using var store = fixture.OpenStore();
        fixture.Apply(store, 1, original); store.MarkScheduleInventoryAcknowledged(1);
        var operationId = ScheduleIdentity.Occurrence(original.ScheduleId, 1, at);
        store.ClaimScheduleOccurrence(original, new ScheduleOccurrenceReport
        {
            ScheduleId = original.ScheduleId, HostId = original.HostId, ServerId = original.ServerId!.Value, Revision = 1,
            ScheduledAtUtc = at, OperationId = operationId, WorkflowId = ScheduleIdentity.Uuid5(operationId, "workflow"),
            ReservationId = ScheduleIdentity.Uuid5(operationId, "reservation"), AppliedInventoryRevision = 1,
            DefinitionSha256 = ControlProtocol.Hash(ControlJson.Serialize(original)), Status = ScheduleOccurrenceStatus.Pending,
            UpdatedAtUtc = at
        });
        fixture.Apply(store, 2, original with { Revision = 2, Enabled = false });
        await fixture.Service(store).TickAsync();
        Assert.Empty(fixture.Runner.Executions);
        Assert.Equal(ScheduleOccurrenceStatus.Skipped, Assert.Single(store.ReadScheduleReports()).Report.Status);
    }

    [Fact]
    public async Task CentralHoldConflictSkipsWithoutStartingAndFailedStepStopsChain()
    {
        using var fixture = new Fixture();
        var schedule = fixture.Schedule(new ScheduleTrigger { Kind = ScheduleTriggerKind.Once, AtUtc = fixture.Clock.GetUtcNow() },
            new ScheduleStep { StepKey = "first", Kind = OperationKind.Start },
            new ScheduleStep { StepKey = "second", Kind = OperationKind.Stop });
        using var store = fixture.OpenStore();
        fixture.Apply(store, 1, schedule); store.MarkScheduleInventoryAcknowledged(1);
        fixture.Runner.CentralHold = true;
        await fixture.Service(store).TickAsync();
        Assert.Equal(ScheduleOccurrenceStatus.Skipped, Assert.Single(store.ReadScheduleReports()).Report.Status);
        Assert.Empty(fixture.Runner.Executions);

        var retry = schedule with { Revision = 2 };
        fixture.Apply(store, 2, retry); store.MarkScheduleInventoryAcknowledged(2);
        fixture.Runner.CentralHold = false;
        fixture.Runner.FailKind = OperationKind.Start;
        await fixture.Service(store).TickAsync();
        var report = store.ReadScheduleReports().Single(x => x.Report.Revision == 2).Report;
        Assert.Equal(ScheduleOccurrenceStatus.Failed, report.Status);
        Assert.Single(report.Steps);
        Assert.Equal(ScheduleIdentity.Child(report.WorkflowId, "first", 1), report.Steps[0].OperationId);
        Assert.Single(fixture.Runner.Executions);
        Assert.Empty(fixture.Runner.Holds);
    }

    [Fact]
    public async Task HeldOccurrenceRecoversFenceBeforeReconcile()
    {
        using var fixture = new Fixture();
        var schedule = fixture.Schedule(new ScheduleTrigger { Kind = ScheduleTriggerKind.Once, AtUtc = fixture.Clock.GetUtcNow() });
        using var store = fixture.OpenStore();
        fixture.Apply(store, 1, schedule); store.MarkScheduleInventoryAcknowledged(1);
        var operationId = ScheduleIdentity.Occurrence(schedule.ScheduleId, 1, fixture.Clock.GetUtcNow());
        var workflowId = ScheduleIdentity.Uuid5(operationId, "workflow");
        var reservationId = ScheduleIdentity.Uuid5(operationId, "reservation");
        store.ClaimScheduleOccurrence(schedule, new ScheduleOccurrenceReport
        {
            ScheduleId = schedule.ScheduleId, HostId = schedule.HostId, ServerId = schedule.ServerId!.Value, Revision = 1,
            ScheduledAtUtc = fixture.Clock.GetUtcNow(), OperationId = operationId, WorkflowId = workflowId,
            ReservationId = reservationId, AppliedInventoryRevision = 1, DefinitionSha256 = ControlProtocol.Hash(ControlJson.Serialize(schedule)),
            Status = ScheduleOccurrenceStatus.Pending, UpdatedAtUtc = fixture.Clock.GetUtcNow()
        });
        var hold = await fixture.Runner.AcquireHoldAsync(new AcquireHoldRequest(reservationId, workflowId, ["server:" + fixture.ServerId], HoldMode.Exclusive), CancellationToken.None);
        await fixture.Service(store).RefreshHeldReportsAsync();
        var recovered = Assert.Single(store.ReadScheduleReports()).Report;
        Assert.Equal(hold.Fence, recovered.Fence);
        Assert.Equal(hold.ResourceKeys, recovered.ReservedResourceKeys);
        Assert.Equal(ScheduleOccurrenceStatus.Running, recovered.Status);
        Assert.Empty(fixture.Runner.Executions);
    }

    [Fact]
    public async Task ScheduledBackupUsesDataOperationAndDeterministicBackupId()
    {
        using var fixture = new Fixture();
        fixture.Runner.IsRunning = true;
        var schedule = fixture.Schedule(new ScheduleTrigger { Kind = ScheduleTriggerKind.Once, AtUtc = fixture.Clock.GetUtcNow() },
            new ScheduleStep { StepKey = "backup", Kind = OperationKind.CreateBackup,
                Payload = ControlJson.Element(new CreateBackupPayload(Guid.Empty, fixture.ProfileId, 1)) });
        using var store = fixture.OpenStore();
        fixture.Apply(store, 1, schedule, includeBackupProfile: true); store.MarkScheduleInventoryAcknowledged(1);
        var service = fixture.Service(store);
        for (var attempt = 0; attempt < 100; attempt++)
        {
            await service.TickAsync();
            if (store.ReadScheduleReports().Single().Report.Status == ScheduleOccurrenceStatus.Succeeded) break;
            await Task.Delay(20);
        }
        var report = Assert.Single(store.ReadScheduleReports()).Report;
        Assert.Equal(ScheduleOccurrenceStatus.Succeeded, report.Status);
        var child = Assert.Single(report.Steps);
        var backup = Assert.Single(fixture.Storage.ListBackups(fixture.Server));
        Assert.Equal(ScheduleIdentity.Uuid5(child.OperationId, "backup"), backup.BackupId);
        Assert.Equal(BackupState.Completed, backup.State);
        Assert.Contains(fixture.Runner.Executions.Keys, id => id == DataExecution.ChildOperationId(child.OperationId, OperationKind.Stop));
        Assert.False(fixture.Runner.IsRunning);
        Assert.Equal("world", File.ReadAllText(Path.Combine(fixture.BackupRoot, "control-backups", fixture.ServerId.ToString("N"), backup.BackupId.ToString("N"), "files", "level.dat")));
        Assert.Empty(fixture.Runner.Holds);
    }

    private sealed class Fixture : IDisposable
    {
        private readonly string root = Path.Combine(Path.GetTempPath(), "control-schedule-test-" + Guid.NewGuid().ToString("N"));
        public Guid HostId { get; } = Guid.NewGuid();
        public Guid ServerId { get; } = Guid.NewGuid();
        public Guid RootId { get; } = Guid.NewGuid();
        public Guid BackupRootId { get; } = Guid.NewGuid();
        public Guid ProfileId { get; } = Guid.NewGuid();
        public string BackupRoot => Path.Combine(root, "backup-root");
        public ServerConfig Server => new() { HostId = HostId, ServerId = ServerId, Revision = 1,
            ManagedRootId = RootId, ExecutableId = Guid.NewGuid(), ServerDirectory = "server" };
        public P2StorageService Storage { get; }
        public ManualClock Clock { get; } = new(new DateTimeOffset(2026, 10, 18, 12, 0, 0, TimeSpan.Zero));
        public FakeRunner Runner { get; }
        public AgentConfiguration Configuration { get; }
        public AgentProcessControlGate Gate { get; }
        public Fixture()
        {
            Directory.CreateDirectory(root);
            var liveRoot = Path.Combine(root, "managed-root");
            Directory.CreateDirectory(Path.Combine(liveRoot, "server"));
            Directory.CreateDirectory(BackupRoot);
            File.WriteAllText(Path.Combine(liveRoot, "server", "level.dat"), "world");
            var marker = Path.Combine(root, "marker"); File.WriteAllText(marker, "test");
            Gate = new AgentProcessControlGate(marker);
            Configuration = new AgentConfiguration { HostId = HostId, ServerUrl = "https://control.test", EnableSchedules = true, EnableFileControl = true,
                LocalResources = new HostResources { Roots = [new ManagedRoot { Id = RootId, Path = liveRoot, DisplayName = "fixture" },
                    new ManagedRoot { Id = BackupRootId, Path = BackupRoot, DisplayName = "backup", BackupOnly = true }] } };
            Runner = new FakeRunner(HostId, ServerId, Clock);
            Storage = new P2StorageService(Path.Combine(root, "storage"), Configuration.LocalResources, Clock, _ => long.MaxValue);
        }
        public AgentStore OpenStore() => new(Path.Combine(root, "agent"), HostId);
        public AgentScheduleService Service(AgentStore store) => new(Configuration, store, Runner,
            new AgentOperationHandler(Configuration, store, Runner, Gate, Storage, Clock), Gate, Clock);
        public ScheduleDefinition Schedule(ScheduleTrigger trigger, params ScheduleStep[] steps) => new()
        {
            ScheduleId = Guid.NewGuid(), HostId = HostId, ServerId = ServerId, Revision = 1, Enabled = true, Trigger = trigger,
            Template = new ScheduleTemplate { ActionType = steps.Length > 1 ? ScheduleActionType.LocalChain : ScheduleActionType.Operation,
                Steps = steps.Length > 0 ? steps : [new ScheduleStep { StepKey = "first", Kind = OperationKind.Start }] }
        };
        public void Apply(AgentStore store, long revision, ScheduleDefinition schedule, bool includeBackupProfile = false)
        {
            var profile = new BackupProfile { BackupProfileId = ProfileId, ServerId = ServerId, Revision = 1, DestinationRootId = BackupRootId };
            var json = ControlJson.Serialize(new InventorySnapshot { HostId = HostId, Revision = revision, Servers = [Server], Schedules = [ControlJson.Element(schedule)],
                BackupProfiles = includeBackupProfile ? [ControlJson.Element(profile)] : [] });
            var envelope = new InventoryEnvelope(1, HostId, revision, json, ControlProtocol.Hash(json));
            store.PrepareInventory(envelope); store.CommitInventory(envelope);
        }
        public void Dispose() => Directory.Delete(root, true);
    }

    private sealed class ManualClock(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }

    private sealed class FakeRunner(Guid hostId, Guid serverId, ManualClock clock) : IRunnerClient
    {
        private long fence;
        private long stateRevision;
        private readonly Dictionary<Guid, ExecutionSnapshot> executions = [];
        private readonly Dictionary<Guid, ExecutionSnapshot> dataExecutions = [];
        public IReadOnlyDictionary<Guid, ExecutionSnapshot> Executions => executions;
        public Dictionary<Guid, ResourceHold> Holds { get; } = [];
        public bool CentralHold { get; set; }
        public OperationKind? FailKind { get; set; }
        public bool IsRunning { get; set; }
        private readonly Guid sessionId = Guid.NewGuid();
        public Task<RunnerSnapshot> ApplyInventoryAsync(InventoryEnvelope inventory, CancellationToken cancellationToken) => throw new NotImplementedException();
        public Task<RunnerSnapshot> GetSnapshotAsync(CancellationToken cancellationToken) => Task.FromResult(new RunnerSnapshot
        {
            RunnerEpoch = Guid.NewGuid(), Holds = Holds.Values.ToArray(), Servers = [new ServerRuntimeSnapshot { ServerId = serverId, ConfigRevision = 1, StateRevision = stateRevision,
                ProcessState = IsRunning ? ProcessState.Running : ProcessState.Stopped, CurrentSessionId = IsRunning ? sessionId : null }]
        });
        public Task<ResourceHold> AcquireHoldAsync(AcquireHoldRequest request, CancellationToken cancellationToken)
        {
            if (Holds.TryGetValue(request.ReservationId, out var held)) return Task.FromResult(held);
            if (CentralHold || Holds.Count != 0) throw new AgentFault(ErrorCode.ReservationConflict);
            var hold = new ResourceHold { ReservationId = request.ReservationId, OwnerWorkflowId = request.OwnerWorkflowId,
                ResourceKeys = ["server:" + serverId], Mode = request.Mode, Fence = ++fence };
            Holds.Add(hold.ReservationId, hold); stateRevision++;
            return Task.FromResult(hold);
        }
        public Task<bool> ReleaseHoldAsync(ReleaseHoldRequest request, CancellationToken cancellationToken)
        {
            var removed = Holds.Remove(request.ReservationId); if (removed) stateRevision++;
            return Task.FromResult(removed);
        }
        public Task<ExecutionSnapshot> ExecuteAsync(ExecuteRequest request, CancellationToken cancellationToken)
        {
            if (executions.TryGetValue(request.OperationId, out var prior)) return Task.FromResult(prior);
            var command = OperationValidation.Read(request, hostId);
            if (!Holds.TryGetValue(command.ReservationId!.Value, out var hold) || hold.Fence != command.ReservationFence || command.ExpectedStateRevision != stateRevision)
                throw new AgentFault(ErrorCode.ReservationConflict);
            var status = command.Kind == FailKind ? OperationStatus.Failed : OperationStatus.Succeeded;
            if (status == OperationStatus.Succeeded && command.Kind == OperationKind.Stop) { IsRunning = false; stateRevision++; }
            var result = new ExecutionSnapshot { OperationId = request.OperationId, CommandSha256 = request.CommandSha256,
                Status = status, StatusVersion = 2, Phase = ExecutionPhase.Completed, UpdatedAtUtc = clock.GetUtcNow() };
            executions.Add(request.OperationId, result);
            return Task.FromResult(result);
        }
        public Task<ExecutionSnapshot> GetExecutionAsync(Guid operationId, CancellationToken cancellationToken) =>
            executions.TryGetValue(operationId, out var result) ? Task.FromResult(result) : throw new AgentFault(ErrorCode.StateConflict);
        public Task<ExecutionSnapshot> BeginDataOperationAsync(ExecuteRequest request, CancellationToken cancellationToken)
        {
            var operation = OperationValidation.Read(request, hostId);
            if (!Holds.ContainsKey(operation.ReservationId!.Value)) throw new AgentFault(ErrorCode.ReservationConflict);
            if (!dataExecutions.TryGetValue(request.OperationId, out var result))
            {
                result = new ExecutionSnapshot { OperationId = request.OperationId, CommandSha256 = request.CommandSha256,
                    Status = OperationStatus.Running, StatusVersion = 2, Phase = ExecutionPhase.Running, UpdatedAtUtc = clock.GetUtcNow() };
                dataExecutions.Add(request.OperationId, result);
            }
            return Task.FromResult(result);
        }
        public Task<ExecutionSnapshot> VerifyDataOperationAsync(Guid operationId, CancellationToken cancellationToken) => GetDataOperationAsync(operationId, cancellationToken);
        public Task<ExecutionSnapshot> FinishDataOperationAsync(FinishDataOperationRequest request, CancellationToken cancellationToken)
        {
            var previous = dataExecutions[request.OperationId];
            var next = previous with { Status = request.Status, StatusVersion = previous.StatusVersion + 1, Phase = ExecutionPhase.Completed };
            dataExecutions[request.OperationId] = next;
            return Task.FromResult(next);
        }
        public Task<ExecutionSnapshot> GetDataOperationAsync(Guid operationId, CancellationToken cancellationToken) =>
            dataExecutions.TryGetValue(operationId, out var result) ? Task.FromResult(result) : throw new AgentFault(ErrorCode.StateConflict);
    }
}
