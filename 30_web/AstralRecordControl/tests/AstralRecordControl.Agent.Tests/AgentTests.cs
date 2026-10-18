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
