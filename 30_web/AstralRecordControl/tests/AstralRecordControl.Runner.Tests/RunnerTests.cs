using System.Buffers.Binary;
using System.Text;
using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;
using AstralRecordControl.Contracts;
using AstralRecordControl.Runner;
using Microsoft.Data.Sqlite;
using Xunit;

namespace AstralRecordControl.Runner.Tests;

public sealed class RunnerTests
{
    [Fact]
    public void ReleasedReservationCannotBeResurrectedAfterRestartAndReleaseReplayIsIdempotent()
    {
        using var fixture = new Fixture();
        var request = new AcquireHoldRequest(Guid.NewGuid(), Guid.NewGuid(), ["server:" + fixture.Server.ServerId], HoldMode.Maintenance);
        ResourceHold hold;
        using (var runner = fixture.Open())
        {
            runner.ApplyInventory(fixture.Envelope(1, fixture.Server));
            hold = runner.AcquireHold(request);
            runner.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
        }
        using var restarted = fixture.Open();
        restarted.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
        Assert.Empty(restarted.GetSnapshot().Holds);
        Assert.Equal(ErrorCode.ReservationConflict, Assert.Throws<RunnerFault>(() => restarted.AcquireHold(request)).Code);
        Assert.Throws<RunnerFault>(() => restarted.ReleaseHold(new(hold.ReservationId, Guid.NewGuid(), hold.Fence)));
        Assert.Throws<RunnerFault>(() => restarted.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence + 1)));
    }

    [Fact]
    public void UnversionedExistingSchemaIsRejectedWithoutChangingItsContents()
    {
        using var fixture = new Fixture(); var data = Path.Combine(fixture.Root, "runner"); Directory.CreateDirectory(data);
        using var connection = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = Path.Combine(data, "runner.db"), Pooling = false }.ToString());
        connection.Open();
        using var command = connection.CreateCommand();
        command.CommandText = "CREATE TABLE LegacyData(value TEXT); INSERT INTO LegacyData VALUES('preserve');"; command.ExecuteNonQuery();
        Assert.Equal(ErrorCode.RecoveryRequired, Assert.Throws<RunnerFault>(() => fixture.Open()).Code);
        command.CommandText = "SELECT value FROM LegacyData;"; Assert.Equal("preserve", command.ExecuteScalar());
        command.CommandText = "SELECT COUNT(*) FROM sqlite_master WHERE name='State';"; Assert.Equal(0L, command.ExecuteScalar());
    }

    [Fact]
    public void SchemaOneMigratesTransactionallyWithIndependentBackup()
    {
        using var fixture = new Fixture();
        var data = Path.Combine(fixture.Root, "runner"); Directory.CreateDirectory(data);
        var path = Path.Combine(data, "runner.db");
        using (var database = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path, Pooling = false }.ToString()))
        {
            database.Open();
            using var schema = database.CreateCommand();
            schema.CommandText = "CREATE TABLE State(id INTEGER PRIMARY KEY CHECK(id=1),hostId TEXT NOT NULL,epoch TEXT NOT NULL,bootUtc TEXT NOT NULL,revision INTEGER NOT NULL,hash TEXT NOT NULL,snapshot TEXT NOT NULL,nextFence INTEGER NOT NULL); CREATE TABLE Servers(id TEXT PRIMARY KEY,config TEXT NOT NULL,stateRevision INTEGER NOT NULL); CREATE TABLE Holds(resource TEXT PRIMARY KEY,reservation TEXT NOT NULL,workflow TEXT NOT NULL,fence INTEGER NOT NULL,mode TEXT NOT NULL,createdUtc TEXT NOT NULL); CREATE TABLE ReleasedHolds(reservation TEXT PRIMARY KEY,workflow TEXT NOT NULL,fence INTEGER NOT NULL); INSERT INTO State VALUES(1,$host,$epoch,$utc,0,'','',0); PRAGMA user_version=1;";
            schema.Parameters.AddWithValue("$host", fixture.Configuration.HostId.ToString());
            schema.Parameters.AddWithValue("$epoch", Guid.NewGuid().ToString());
            schema.Parameters.AddWithValue("$utc", DateTimeOffset.UtcNow.ToString("O"));
            schema.ExecuteNonQuery();
        }
        using (var migrated = fixture.Open()) Assert.Empty(migrated.GetSnapshot().Servers);
        using (var database = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path, Pooling = false }.ToString()))
        {
            database.Open(); using var version = database.CreateCommand(); version.CommandText = "PRAGMA user_version;";
            Assert.Equal(4L, version.ExecuteScalar());
        }
        var backup = Assert.Single(Directory.GetFiles(data, "runner.db.before-v2.*.bak"));
        using var saved = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = backup, Pooling = false }.ToString());
        saved.Open(); using var prior = saved.CreateCommand(); prior.CommandText = "PRAGMA user_version;";
        Assert.Equal(1L, prior.ExecuteScalar());
    }

    [Fact]
    public void RestartRestoresInventoryHoldStateAndFenceButChangesEpoch()
    {
        using var fixture = new Fixture();
        var request = new AcquireHoldRequest(Guid.NewGuid(), Guid.NewGuid(), ["server:" + fixture.Server.ServerId], HoldMode.Maintenance);
        Guid epoch;
        ResourceHold hold;
        long stateRevision;
        using (var runner = fixture.Open())
        {
            runner.ApplyInventory(fixture.Envelope(1, fixture.Server));
            hold = runner.AcquireHold(request);
            Assert.Equivalent(hold, runner.AcquireHold(request));
            var snapshot = runner.GetSnapshot(); epoch = snapshot.RunnerEpoch; stateRevision = snapshot.Servers[0].StateRevision;
            Assert.True(stateRevision > 0);
            Assert.Contains(hold.ResourceKeys, key => key.StartsWith("directory:", StringComparison.Ordinal));
        }
        using var restarted = fixture.Open();
        var restored = restarted.GetSnapshot();
        Assert.NotEqual(epoch, restored.RunnerEpoch);
        Assert.Equal(1, restored.AppliedInventoryRevision);
        Assert.Equal(stateRevision, restored.Servers[0].StateRevision);
        Assert.Equal(hold.Fence, Assert.Single(restored.Holds).Fence);
        Assert.Equal(ErrorCode.ReservationConflict, Assert.Throws<RunnerFault>(() => restarted.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence + 1))).Code);
        restarted.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
        Assert.Empty(restarted.GetSnapshot().Holds);
        Assert.True(restarted.GetSnapshot().Servers[0].StateRevision > stateRevision);
        Assert.True(restarted.AcquireHold(request with { ReservationId = Guid.NewGuid() }).Fence > hold.Fence);
    }

    [Fact]
    public void InvalidHashSchemaAndSameRevisionConflictPreserveAppliedInventory()
    {
        using var fixture = new Fixture(); using var runner = fixture.Open();
        var original = fixture.Envelope(1, fixture.Server); runner.ApplyInventory(original);
        Assert.Throws<RunnerFault>(() => runner.ApplyInventory(original with { Sha256 = new string('0', 64) }));
        var invalid = new InventorySnapshot { SchemaVersion = 2, HostId = fixture.Configuration.HostId, Revision = 2 };
        var json = ControlJson.Serialize(invalid);
        Assert.Throws<RunnerFault>(() => runner.ApplyInventory(new(1, invalid.HostId, 2, json, ControlProtocol.Hash(json))));
        Assert.Equal(ErrorCode.OperationConflict, Assert.Throws<RunnerFault>(() => runner.ApplyInventory(fixture.Envelope(1, fixture.Server with { Revision = 2 }))).Code);
        Assert.Equal(1, runner.GetSnapshot().AppliedInventoryRevision);
        Assert.Equal(1, runner.GetSnapshot().Servers[0].ConfigRevision);
    }

    [Fact]
    public void AppliedConfigurationChangeIncrementsStateRevisionAndIdenticalReplayDoesNot()
    {
        using var fixture = new Fixture(); using var runner = fixture.Open();
        runner.ApplyInventory(fixture.Envelope(1, fixture.Server));
        var original = Assert.Single(runner.GetSnapshot().Servers).StateRevision;
        var next = fixture.Envelope(2, fixture.Server with { Revision = 2, StopCommand = "end" });
        runner.ApplyInventory(next);
        var updated = Assert.Single(runner.GetSnapshot().Servers).StateRevision;
        Assert.True(updated > original);
        runner.ApplyInventory(next);
        Assert.Equal(updated, Assert.Single(runner.GetSnapshot().Servers).StateRevision);
    }

    [Fact]
    public void DatabaseTransactionRollsBackEarlierRowsWhenLaterConfigFails()
    {
        using var fixture = new Fixture(); using var runner = fixture.Open();
        var second = fixture.Server with { ServerId = Guid.NewGuid(), ServerDirectory = "second", Revision = 2 };
        runner.ApplyInventory(fixture.Envelope(1, fixture.Server, second));
        Assert.Throws<RunnerFault>(() => runner.ApplyInventory(fixture.Envelope(2, fixture.Server with { Revision = 2 }, second with { Revision = 1 })));
        var current = runner.GetSnapshot();
        Assert.Equal(1, current.AppliedInventoryRevision);
        Assert.Equal(1, current.Servers.Single(s => s.ServerId == fixture.Server.ServerId).ConfigRevision);
        Assert.Equal(2, current.Servers.Single(s => s.ServerId == second.ServerId).ConfigRevision);
    }

    [Fact]
    public void DirectoryParentAndCaseAliasesConflictWithAutomaticServerRoot()
    {
        using var fixture = new Fixture(); using var runner = fixture.Open();
        runner.ApplyInventory(fixture.Envelope(1, fixture.Server));
        var parent = $"directory:{fixture.Configuration.HostId}:{fixture.Server.ManagedRootId}:SERVER/child";
        runner.AcquireHold(new(Guid.NewGuid(), Guid.NewGuid(), [parent], HoldMode.Exclusive));
        Assert.Equal(ErrorCode.ReservationConflict, Assert.Throws<RunnerFault>(() => runner.AcquireHold(new(Guid.NewGuid(), Guid.NewGuid(), ["server:" + fixture.Server.ServerId], HoldMode.Exclusive))).Code);
        Assert.Single(runner.GetSnapshot().Holds);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void ActiveServerOrDirectoryHoldRejectsConfigurationMutationAndRemoval(bool serverResource)
    {
        using var fixture = new Fixture(); using var runner = fixture.Open();
        runner.ApplyInventory(fixture.Envelope(1, fixture.Server));
        var resource = serverResource ? "server:" + fixture.Server.ServerId : $"directory:{fixture.Configuration.HostId}:{fixture.Server.ManagedRootId}:server/child";
        runner.AcquireHold(new(Guid.NewGuid(), Guid.NewGuid(), [resource], HoldMode.Maintenance));
        Assert.Equal(ErrorCode.ReservationConflict, Assert.Throws<RunnerFault>(() => runner.ApplyInventory(fixture.Envelope(2, fixture.Server with { Revision = 2, ServerDirectory = "new-path" }))).Code);
        Assert.Equal(ErrorCode.ReservationConflict, Assert.Throws<RunnerFault>(() => runner.ApplyInventory(fixture.Envelope(2))).Code);
        var unchanged = runner.GetSnapshot();
        Assert.Equal(1, unchanged.AppliedInventoryRevision);
        Assert.Equal(1, Assert.Single(unchanged.Servers).ConfigRevision);
        Assert.Single(unchanged.Holds);
        runner.ApplyInventory(fixture.Envelope(1, fixture.Server));
    }

    [Fact]
    public void ExecuteNeverRunsAProcessAndUnknownProtocolIsRejected()
    {
        using var fixture = new Fixture(); using var store = fixture.Open(); var dispatcher = new RunnerDispatcher(store);
        var request = new IpcRequest(1, Guid.NewGuid(), "Execute", ControlJson.Element(new { command = "anything" }));
        Assert.Equal("UnsupportedCapability", dispatcher.Dispatch(request).ErrorCode);
        Assert.Equal("ProtocolMismatch", dispatcher.Dispatch(request with { ProtocolVersion = 2 }).ErrorCode);
        Assert.Equal("ProtocolMismatch", dispatcher.Dispatch(request with { Method = "GetSnapshot", Body = ControlJson.Element(new { unexpected = 1 }) }).ErrorCode);
        Assert.Empty(store.GetSnapshot().Servers);
    }

    [Theory]
    [InlineData(0u)]
    [InlineData(1048577u)]
    [InlineData(uint.MaxValue)]
    public async Task PipeRejectsInvalidLengthBeforeReadingBody(uint length)
    {
        var header = new byte[4]; BinaryPrimitives.WriteUInt32LittleEndian(header, length);
        using var input = new MemoryStream(header);
        await Assert.ThrowsAsync<RunnerFault>(() => PipeProtocol.ReadAsync(input, CancellationToken.None));
    }

    [Fact]
    public async Task PipeRejectsInvalidUtf8AndTruncationAndRoundTripsValidFrame()
    {
        using var invalid = new MemoryStream([1, 0, 0, 0, 255]);
        await Assert.ThrowsAsync<DecoderFallbackException>(() => PipeProtocol.ReadAsync(invalid, CancellationToken.None));
        using var truncated = new MemoryStream([3, 0, 0, 0, 65]);
        await Assert.ThrowsAsync<EndOfStreamException>(() => PipeProtocol.ReadAsync(truncated, CancellationToken.None));
        using var valid = new MemoryStream();
        await PipeProtocol.WriteAsync(valid, new { message = "日本語" }, CancellationToken.None); valid.Position = 0;
        Assert.Contains("message", await PipeProtocol.ReadAsync(valid, CancellationToken.None));
    }

    [Fact]
    public async Task LocalNamedPipeUsesRestrictedAclAndCarriesFramedContractMessages()
    {
        using var fixture = new Fixture(); using var store = fixture.Open();
        using var identity = WindowsIdentity.GetCurrent();
        var sid = identity.User!;
        var configuration = fixture.Configuration with { AgentSid = sid.Value, PipeName = "Control.Runner.Test." + Guid.NewGuid().ToString("N") };
        using var server = RunnerService.CreatePipe(configuration);
        var security = server.GetAccessControl();
        Assert.True(security.AreAccessRulesProtected);
        var rules = security.GetAccessRules(true, true, typeof(SecurityIdentifier)).Cast<PipeAccessRule>().ToArray();
        Assert.Contains(rules, r => r.IdentityReference.Equals(new SecurityIdentifier(WellKnownSidType.NetworkSid, null)) && r.AccessControlType == AccessControlType.Deny);
        Assert.All(rules.Where(r => r.AccessControlType == AccessControlType.Allow), r => Assert.True(r.IdentityReference.Equals(sid) || r.IdentityReference.Equals(new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null))));
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var receive = Task.Run(async () =>
        {
            await server.WaitForConnectionAsync(timeout.Token);
            var request = ControlJson.Deserialize<IpcRequest>(await PipeProtocol.ReadAsync(server, timeout.Token));
            await PipeProtocol.WriteAsync(server, new RunnerDispatcher(store).Dispatch(request), timeout.Token);
        });
        using var client = new NamedPipeClientStream(".", configuration.PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
        await client.ConnectAsync(timeout.Token);
        var message = new IpcRequest(1, Guid.NewGuid(), "GetSnapshot", ControlJson.Element(new { }));
        await PipeProtocol.WriteAsync(client, message, timeout.Token);
        var response = ControlJson.Deserialize<IpcResponse>(await PipeProtocol.ReadAsync(client, timeout.Token));
        Assert.True(response.Ok); Assert.Equal(message.RequestId, response.RequestId);
        Assert.Equal(store.Epoch, ControlJson.Deserialize<RunnerSnapshot>(response.Body.GetRawText()).RunnerEpoch);
        await receive;
    }

    [Fact]
    public void MissingDestinationUnderLinkedAncestorAndEscapingArgumentsAreRejected()
    {
        using var fixture = new Fixture();
        var outside = Path.Combine(fixture.Root, "outside"); Directory.CreateDirectory(outside);
        var link = Path.Combine(fixture.ManagedRoot, "linked");
        // Junction creation does not require Windows developer mode or symlink privileges.
        using var process = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("cmd.exe") { UseShellExecute = false, CreateNoWindow = true, ArgumentList = { "/c", "mklink", "/J", link, outside }, RedirectStandardOutput = true, RedirectStandardError = true })!;
        process.WaitForExit(); Assert.Equal(0, process.ExitCode);
        try
        {
            Assert.Equal(ErrorCode.PathOutsideRoot, Assert.Throws<RunnerFault>(() => LocalResourceBoundary.Resolve(fixture.ManagedRoot, "linked/missing/child")).Code);
        }
        finally { Directory.Delete(link); }
        var bad = fixture.Server with { ArgumentList = ["-jar", "../outside.jar"] };
        Assert.Throws<RunnerFault>(() => LocalResourceBoundary.ValidateInventory(new InventorySnapshot { HostId = fixture.Configuration.HostId, Servers = [bad] }, fixture.Configuration.LocalResources));
        Assert.Throws<RunnerFault>(() => LocalResourceBoundary.Resolve(fixture.ManagedRoot, "../outside"));
    }

    private sealed class Fixture : IDisposable
    {
        public string Root { get; } = Path.Combine(Path.GetTempPath(), "control-runner-test-" + Guid.NewGuid().ToString("N"));
        public string ManagedRoot { get; }
        public RunnerConfiguration Configuration { get; }
        public ServerConfig Server { get; }
        public Fixture()
        {
            ManagedRoot = Path.Combine(Root, "managed"); Directory.CreateDirectory(ManagedRoot);
            var executable = Path.Combine(Root, "fake-java.exe"); File.WriteAllText(executable, "fixture; never executable");
            var rootId = Guid.NewGuid(); var executableId = Guid.NewGuid(); var host = Guid.NewGuid();
            Configuration = new RunnerConfiguration { HostId = host, LocalResources = new HostResources { Roots = [new ManagedRoot { Id = rootId, Path = ManagedRoot }], Executables = [new ManagedExecutable { Id = executableId, Path = executable, Kind = ServerKind.Java }] } };
            Server = new ServerConfig { HostId = host, ServerId = Guid.NewGuid(), Revision = 1, ManagedRootId = rootId, ExecutableId = executableId, ServerDirectory = "server", Kind = ServerKind.Java };
        }
        public RunnerStore Open() => new(Path.Combine(Root, "runner"), Configuration);
        public InventoryEnvelope Envelope(long revision, params ServerConfig[] servers)
        {
            var snapshot = new InventorySnapshot { HostId = Configuration.HostId, Revision = revision, Servers = servers };
            var json = ControlJson.Serialize(snapshot); return new(1, Configuration.HostId, revision, json, ControlProtocol.Hash(json));
        }
        public void Dispose() => Directory.Delete(Root, true);
    }
}
