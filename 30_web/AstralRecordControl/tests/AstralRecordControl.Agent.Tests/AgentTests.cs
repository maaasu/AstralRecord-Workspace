using AstralRecordControl.Agent;
using AstralRecordControl.Contracts;
using Microsoft.Data.Sqlite;
using Xunit;

namespace AstralRecordControl.Agent.Tests;

public sealed class AgentTests
{
    [Fact]
    public void UnversionedExistingSchemaIsRejectedWithoutChangingItsContents()
    {
        using var fixture = new Fixture(); Directory.CreateDirectory(fixture.DataRoot);
        using var connection = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = Path.Combine(fixture.DataRoot, "agent.db"), Pooling = false }.ToString());
        connection.Open();
        using var command = connection.CreateCommand();
        command.CommandText = "CREATE TABLE LegacyData(value TEXT); INSERT INTO LegacyData VALUES('preserve');"; command.ExecuteNonQuery();
        Assert.Equal(ErrorCode.RecoveryRequired, Assert.Throws<AgentFault>(() => fixture.Open()).Code);
        command.CommandText = "SELECT value FROM LegacyData;"; Assert.Equal("preserve", command.ExecuteScalar());
        command.CommandText = "SELECT COUNT(*) FROM sqlite_master WHERE name='State';"; Assert.Equal(0L, command.ExecuteScalar());
    }

    [Fact]
    public void StoreIdentitySequenceOutboxAndAcknowledgementsSurviveRestart()
    {
        using var fixture = new Fixture(); Guid id;
        using (var store = fixture.Open())
        {
            id = store.GetState().StoreId;
            Assert.Equal(1, store.AppendEvent("fixture", ControlJson.Element(new { })).EventSeq);
            store.AppendEvent("fixture", ControlJson.Element(new { }));
            store.Acknowledge(1);
        }
        using var reopened = fixture.Open();
        Assert.Equal(id, reopened.GetState().StoreId);
        Assert.Equal(2, reopened.GetState().MaxEventSequence);
        Assert.Equal(1, reopened.GetState().ControllerAckSequence);
        Assert.Equal(2, Assert.Single(reopened.ReadEvents()).EventSeq);
        Assert.Equal(ErrorCode.RecoveryRequired, Assert.Throws<AgentFault>(() => reopened.Acknowledge(3)).Code);
        Assert.Equal(ErrorCode.RecoveryRequired, Assert.Throws<AgentFault>(() => reopened.Acknowledge(0)).Code);
        reopened.Acknowledge(2);
        Assert.Empty(reopened.ReadEvents());
        Assert.Equal(3, reopened.AppendEvent("fixture", ControlJson.Element(new { })).EventSeq);
    }

    [Fact]
    public async Task InventoryIsPreparedThenRunnerAppliedThenCommittedLocally()
    {
        using var fixture = new Fixture(); using var store = fixture.Open();
        var runner = new FakeRunner
        {
            Apply = envelope =>
            {
                Assert.Equal(0, store.GetState().AppliedInventoryRevision);
                Assert.Equal(envelope.Sha256, store.GetPendingInventory()!.Sha256);
                return new RunnerSnapshot { RunnerEpoch = Guid.NewGuid(), AppliedInventoryRevision = envelope.Revision };
            }
        };
        var envelope = fixture.Envelope(1);
        await new InventoryApplier(fixture.Configuration, store, runner).ApplyAsync(envelope, CancellationToken.None);
        Assert.Equal(1, store.GetState().AppliedInventoryRevision);
        Assert.Equal(envelope.Sha256, store.GetState().InventorySha256);
        Assert.Null(store.GetPendingInventory());
        Assert.Equal(1, runner.ApplyCount);
    }

    [Fact]
    public async Task RunnerFailureLeavesOldAppliedConfigAndPendingJournalForRetry()
    {
        using var fixture = new Fixture(); using var store = fixture.Open();
        var runner = new FakeRunner(); var applier = new InventoryApplier(fixture.Configuration, store, runner);
        await applier.ApplyAsync(fixture.Envelope(1), CancellationToken.None);
        runner.Apply = _ => throw new IOException("fixture failure");
        await Assert.ThrowsAsync<IOException>(() => applier.ApplyAsync(fixture.Envelope(2), CancellationToken.None));
        Assert.Equal(1, store.GetState().AppliedInventoryRevision);
        Assert.Equal(2, store.GetPendingInventory()!.Revision);
        runner.Apply = envelope => new RunnerSnapshot { RunnerEpoch = Guid.NewGuid(), AppliedInventoryRevision = envelope.Revision };
        await applier.ApplyAsync(fixture.Envelope(2), CancellationToken.None);
        Assert.Equal(2, store.GetState().AppliedInventoryRevision);
        Assert.Null(store.GetPendingInventory());
    }

    [Fact]
    public async Task AgentCommitFailureDoesNotAckAndJournalCanRecoverAfterRunnerAdvanced()
    {
        using var fixture = new Fixture(); using var store = fixture.Open();
        var runner = new FakeRunner(); var applier = new InventoryApplier(fixture.Configuration, store, runner);
        using var injected = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = Path.Combine(fixture.DataRoot, "agent.db"), Pooling = false }.ToString());
        injected.Open();
        using (var command = injected.CreateCommand())
        {
            command.CommandText = "CREATE TRIGGER abort_commit BEFORE UPDATE OF revision ON State BEGIN SELECT RAISE(ABORT,'fixture'); END;"; command.ExecuteNonQuery();
        }
        await Assert.ThrowsAsync<SqliteException>(() => applier.ApplyAsync(fixture.Envelope(1), CancellationToken.None));
        Assert.Equal(1, runner.ApplyCount);
        Assert.Equal(0, store.GetState().AppliedInventoryRevision);
        Assert.NotNull(store.GetPendingInventory());
        using (var command = injected.CreateCommand()) { command.CommandText = "DROP TRIGGER abort_commit;"; command.ExecuteNonQuery(); }
        await applier.ApplyAsync(fixture.Envelope(1), CancellationToken.None);
        Assert.Equal(1, store.GetState().AppliedInventoryRevision);
        Assert.Null(store.GetPendingInventory());
    }

    [Fact]
    public async Task UnknownSchemaAndBadHashNeverReachRunnerAndSameRevisionDiffHashFails()
    {
        using var fixture = new Fixture(); using var store = fixture.Open(); var runner = new FakeRunner();
        var applier = new InventoryApplier(fixture.Configuration, store, runner);
        var envelope = fixture.Envelope(1);
        await Assert.ThrowsAsync<ArgumentException>(() => applier.ApplyAsync(envelope with { Sha256 = new string('0', 64) }, CancellationToken.None));
        var json = envelope.SnapshotJson.Replace("\"schemaVersion\":1", "\"schemaVersion\":2", StringComparison.Ordinal);
        await Assert.ThrowsAsync<ArgumentException>(() => applier.ApplyAsync(envelope with { SnapshotJson = json, Sha256 = ControlProtocol.Hash(json) }, CancellationToken.None));
        Assert.Equal(0, runner.ApplyCount);
        await applier.ApplyAsync(envelope, CancellationToken.None);
        var altered = envelope.SnapshotJson.Replace("\"servers\":[]", "\"servers\": []", StringComparison.Ordinal);
        await Assert.ThrowsAsync<AgentFault>(() => applier.ApplyAsync(envelope with { SnapshotJson = altered, Sha256 = ControlProtocol.Hash(altered) }, CancellationToken.None));
        Assert.Equal(1, runner.ApplyCount);
    }

    [Theory]
    [InlineData("http://localhost")]
    [InlineData("https://user:password@example.com")]
    [InlineData("https://example.com?token=bad")]
    [InlineData("https://example.com/path")]
    [InlineData("https://example.com/#fragment")]
    public void ConfigurationRejectsNonHttpsOriginsAndSecretInUrl(string origin)
    {
        Assert.Throws<ArgumentException>(() => new AgentConfiguration { HostId = Guid.NewGuid(), ServerUrl = origin }.Validate());
    }

    [Fact]
    public void BackoffContinuesAfterInitialAndLongTermFailuresWithBoundedJitter()
    {
        var expected = new[] { 1, 2, 5, 10, 30, 60, 60, 60 };
        for (var index = 0; index < expected.Length; index++)
        {
            Assert.Equal(TimeSpan.FromSeconds(expected[index]), ReconnectBackoff.Delay(index, 0));
            Assert.Equal(TimeSpan.FromSeconds(expected[index] * 1.2), ReconnectBackoff.Delay(index, 1));
        }
        Assert.Equal(TimeSpan.FromSeconds(60), ReconnectBackoff.Delay(1000, 0));
    }

    [Fact]
    public async Task OperationInboxSurvivesRestartAndDuplicateNeverExecutesAgain()
    {
        using var fixture = new Fixture();
        var command = new OperationCommand { OperationId = Guid.NewGuid(), HostId = fixture.Configuration.HostId, ServerId = Guid.NewGuid(), WorkflowId = Guid.NewGuid(), ReservationId = Guid.NewGuid(), ReservationFence = 7, Kind = OperationKind.Start, ConfigRevision = 1, ExpectedStateRevision = 2, StartBeforeUtc = DateTimeOffset.UtcNow.AddMinutes(1) };
        var json = ControlJson.Serialize(command);
        var epoch = Guid.NewGuid();
        var dispatch = new DispatchOperationRequest(1, epoch, command.OperationId, json, ControlProtocol.Hash(json));
        var runner = new OperationRunner();
        using (var store = fixture.Open())
        {
            var handler = new AgentOperationHandler(fixture.Configuration, store, runner);
            Assert.Equal(OperationStatus.Running, (await handler.DispatchAsync(dispatch, epoch, CancellationToken.None)).Status);
            Assert.Equal(1, runner.ExecuteCount);
            Assert.Equal(2, store.ReadEvents().Length);
        }
        using (var reopened = fixture.Open())
        {
            var handler = new AgentOperationHandler(fixture.Configuration, reopened, runner);
            Assert.Equal(OperationStatus.Running, (await handler.DispatchAsync(dispatch, epoch, CancellationToken.None)).Status);
            Assert.Equal(1, runner.ExecuteCount);
            Assert.Single(reopened.ReadNonTerminalOperations());
            await Assert.ThrowsAsync<ArgumentException>(() => handler.DispatchAsync(dispatch with { CommandSha256 = new string('0', 64) }, epoch, CancellationToken.None));
            await Assert.ThrowsAsync<AgentFault>(() => handler.DispatchAsync(dispatch, Guid.NewGuid(), CancellationToken.None));
        }
    }

    [Fact]
    public async Task AcceptedBeforeRunnerDispatchIsResentOnceWithOriginalIdentity()
    {
        using var fixture = new Fixture();
        var command = new OperationCommand { OperationId = Guid.NewGuid(), HostId = fixture.Configuration.HostId, ServerId = Guid.NewGuid(), WorkflowId = Guid.NewGuid(), ReservationId = Guid.NewGuid(), ReservationFence = 1, Kind = OperationKind.Start, ConfigRevision = 1, ExpectedStateRevision = 2, StartBeforeUtc = DateTimeOffset.UtcNow.AddMinutes(1) };
        var json = ControlJson.Serialize(command);
        var request = new ExecuteRequest(command.OperationId, json, ControlProtocol.Hash(json));
        using (var store = fixture.Open()) store.AcceptOperation(request);
        var runner = new OperationRunner();
        using var reopened = fixture.Open();
        var epoch = Guid.NewGuid();
        var handler = new AgentOperationHandler(fixture.Configuration, reopened, runner);
        var result = await handler.DispatchAsync(new DispatchOperationRequest(1, epoch, request.OperationId, json, request.CommandSha256), epoch, CancellationToken.None);
        Assert.Equal(OperationStatus.Running, result.Status);
        Assert.Equal(1, runner.ExecuteCount);
        Assert.True(reopened.GetDispatch(request.OperationId).DispatchAttempted);
    }

    [Fact]
    public async Task AttemptedDispatchWithMissingRunnerLedgerResendsOriginalCommand()
    {
        using var fixture = new Fixture();
        var command = new OperationCommand { OperationId = Guid.NewGuid(), HostId = fixture.Configuration.HostId, ServerId = Guid.NewGuid(), WorkflowId = Guid.NewGuid(), ReservationId = Guid.NewGuid(), ReservationFence = 1, Kind = OperationKind.ConsoleCommand, ConfigRevision = 1, ExpectedStateRevision = 2, ExpectedServerSessionId = Guid.NewGuid(), StartBeforeUtc = DateTimeOffset.UtcNow.AddMinutes(1), Payload = ControlJson.Element(new { command = "say test" }) };
        var json = ControlJson.Serialize(command);
        using var store = fixture.Open();
        store.AcceptOperation(new ExecuteRequest(command.OperationId, json, ControlProtocol.Hash(json)));
        store.MarkDispatchAttempted(command.OperationId);
        var runner = new OperationRunner();
        var epoch = Guid.NewGuid();
        var result = await new AgentOperationHandler(fixture.Configuration, store, runner).DispatchAsync(new DispatchOperationRequest(1, epoch, command.OperationId, json, ControlProtocol.Hash(json)), epoch, CancellationToken.None);
        Assert.Equal(OperationStatus.Running, result.Status);
        Assert.Equal(1, runner.ExecuteCount);
        Assert.Equal(ControlProtocol.Hash(json), result.CommandSha256);
    }

    [Fact]
    public async Task AttemptedDispatchWithMissingRunnerLedgerExpiresAfterDeadline()
    {
        using var fixture = new Fixture();
        var command = new OperationCommand { OperationId = Guid.NewGuid(), HostId = fixture.Configuration.HostId, ServerId = Guid.NewGuid(), WorkflowId = Guid.NewGuid(), ReservationId = Guid.NewGuid(), ReservationFence = 1, Kind = OperationKind.Start, ConfigRevision = 1, ExpectedStateRevision = 2, StartBeforeUtc = DateTimeOffset.UtcNow.AddSeconds(-1) };
        var json = ControlJson.Serialize(command);
        using var store = fixture.Open();
        store.AcceptOperation(new ExecuteRequest(command.OperationId, json, ControlProtocol.Hash(json)));
        store.MarkDispatchAttempted(command.OperationId);
        var runner = new OperationRunner();
        var epoch = Guid.NewGuid();
        var result = await new AgentOperationHandler(fixture.Configuration, store, runner).DispatchAsync(new DispatchOperationRequest(1, epoch, command.OperationId, json, ControlProtocol.Hash(json)), epoch, CancellationToken.None);
        Assert.Equal(OperationStatus.Expired, result.Status);
        Assert.Equal(0, runner.ExecuteCount);
    }

    [Fact]
    public async Task UnknownRunnerIoKeepsAcceptedForLaterLedgerInspection()
    {
        using var fixture = new Fixture();
        var command = new OperationCommand { OperationId = Guid.NewGuid(), HostId = fixture.Configuration.HostId, ServerId = Guid.NewGuid(), WorkflowId = Guid.NewGuid(), ReservationId = Guid.NewGuid(), ReservationFence = 1, Kind = OperationKind.Start, ConfigRevision = 1, ExpectedStateRevision = 2, StartBeforeUtc = DateTimeOffset.UtcNow.AddMinutes(1) };
        var json = ControlJson.Serialize(command);
        var runner = new OperationRunner { FailIo = true };
        using var store = fixture.Open();
        var epoch = Guid.NewGuid();
        var result = await new AgentOperationHandler(fixture.Configuration, store, runner).DispatchAsync(new DispatchOperationRequest(1, epoch, command.OperationId, json, ControlProtocol.Hash(json)), epoch, CancellationToken.None);
        Assert.Equal(OperationStatus.Accepted, result.Status);
        Assert.Equal(OperationStatus.Accepted, store.GetOperation(command.OperationId)!.Status);
        Assert.Equal(1, runner.ExecuteCount);
    }

    [Fact]
    public async Task SnapshotWaitsForInFlightPrepareBeforeReportingHolds()
    {
        using var fixture = new Fixture(); using var store = fixture.Open();
        var runner = new BlockingHoldRunner();
        var handler = new AgentOperationHandler(fixture.Configuration, store, runner);
        var epoch = Guid.NewGuid();
        var reservationId = Guid.NewGuid(); var workflowId = Guid.NewGuid();
        var holdRequest = new AcquireHoldRequest(reservationId, workflowId, ["server:" + Guid.NewGuid().ToString("D")], HoldMode.Maintenance);
        var prepare = handler.PrepareHoldAsync(new PrepareHoldRequest(epoch, holdRequest), epoch, CancellationToken.None);
        await runner.AcquireEntered.Task;
        var snapshot = handler.SnapshotAsync(new AgentSnapshotRequest(epoch), epoch, CancellationToken.None);
        await Task.Yield();
        Assert.False(snapshot.IsCompleted);
        runner.CompleteAcquire(new ResourceHold { ReservationId = reservationId, OwnerWorkflowId = workflowId, ResourceKeys = holdRequest.ResourceKeys, Mode = holdRequest.Mode, Fence = 1 });
        await prepare;
        Assert.Contains((await snapshot).Holds, x => x.ReservationId == reservationId);
    }

    private sealed class OperationRunner : IRunnerClient
    {
        public int ExecuteCount { get; private set; }
        public bool FailIo { get; set; }
        private ExecutionSnapshot state = null!;
        public Task<RunnerSnapshot> GetSnapshotAsync(CancellationToken cancellationToken) => Task.FromResult(new RunnerSnapshot { RunnerEpoch = Guid.NewGuid() });
        public Task<RunnerSnapshot> ApplyInventoryAsync(InventoryEnvelope inventory, CancellationToken cancellationToken) => throw new NotImplementedException();
        public Task<ExecutionSnapshot> ExecuteAsync(ExecuteRequest request, CancellationToken cancellationToken)
        {
            ExecuteCount++;
            if (FailIo) throw new IOException("Runner response unknown.");
            state = new ExecutionSnapshot { OperationId = request.OperationId, CommandSha256 = request.CommandSha256, Status = OperationStatus.Running, StatusVersion = 2, UpdatedAtUtc = DateTimeOffset.UtcNow };
            return Task.FromResult(state);
        }
        public Task<ExecutionSnapshot> GetExecutionAsync(Guid operationId, CancellationToken cancellationToken) => FailIo ? throw new IOException("Runner query unavailable.") : state is null ? throw new AgentFault(ErrorCode.StateConflict) : Task.FromResult(state);
    }

    private sealed class BlockingHoldRunner : IRunnerClient
    {
        private readonly TaskCompletionSource<ResourceHold> acquired = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private ResourceHold? hold;
        public TaskCompletionSource AcquireEntered { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public async Task<ResourceHold> AcquireHoldAsync(AcquireHoldRequest request, CancellationToken cancellationToken) { AcquireEntered.SetResult(); hold = await acquired.Task.WaitAsync(cancellationToken); return hold; }
        public void CompleteAcquire(ResourceHold value) => acquired.SetResult(value);
        public Task<RunnerSnapshot> GetSnapshotAsync(CancellationToken cancellationToken) => Task.FromResult(new RunnerSnapshot { RunnerEpoch = Guid.NewGuid(), Holds = hold is null ? [] : [hold] });
        public Task<RunnerSnapshot> ApplyInventoryAsync(InventoryEnvelope inventory, CancellationToken cancellationToken) => throw new NotImplementedException();
    }

    private sealed class FakeRunner : IRunnerClient
    {
        public int ApplyCount { get; private set; }
        public Func<InventoryEnvelope, RunnerSnapshot> Apply { get; set; } = envelope => new RunnerSnapshot { RunnerEpoch = Guid.NewGuid(), AppliedInventoryRevision = envelope.Revision };
        public Task<RunnerSnapshot> ApplyInventoryAsync(InventoryEnvelope envelope, CancellationToken cancellationToken) { ApplyCount++; return Task.FromResult(Apply(envelope)); }
        public Task<RunnerSnapshot> GetSnapshotAsync(CancellationToken cancellationToken) => Task.FromResult(new RunnerSnapshot { RunnerEpoch = Guid.NewGuid() });
    }
    private sealed class Fixture : IDisposable
    {
        public string Root { get; } = Path.Combine(Path.GetTempPath(), "control-agent-test-" + Guid.NewGuid().ToString("N"));
        public string DataRoot => Path.Combine(Root, "agent");
        public AgentConfiguration Configuration { get; } = new() { HostId = Guid.NewGuid(), ServerUrl = "https://control.test" };
        public AgentStore Open() => new(DataRoot, Configuration.HostId);
        public InventoryEnvelope Envelope(long revision)
        {
            var json = ControlJson.Serialize(new InventorySnapshot { HostId = Configuration.HostId, Revision = revision });
            return new(1, Configuration.HostId, revision, json, ControlProtocol.Hash(json));
        }
        public void Dispose() => Directory.Delete(Root, true);
    }
}
