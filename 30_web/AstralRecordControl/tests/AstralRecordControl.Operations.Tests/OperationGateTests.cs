using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Operations;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace AstralRecordControl.Operations.Tests;

public sealed class OperationGateTests
{
    [Fact]
    public async Task ReservationTakesAllResourcesAndReusesTheSameLease()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var request = new ReservationAcquireRequest { WorkflowId = Guid.NewGuid(), ResourceKeys = [ReservationService.ServerKey(server.ServerId)], ServerConditions = [new(server.ServerId, ProcessRequirement.Stopped)] };
        var first = await fixture.Reservations.AcquireAsync(request);
        Assert.Equal(ReservationStatus.Prepared, first.Status);
        Assert.Contains(first.ResourceKeys, x => x.StartsWith("directory:", StringComparison.Ordinal));
        Assert.Contains(first.ResourceKeys, x => x.StartsWith("environment:", StringComparison.Ordinal));
        Assert.Single(first.Fences);
        var replay = await fixture.Reservations.AcquireAsync(request);
        Assert.Equal(first.ReservationId, replay.ReservationId);
        Assert.Equal(1, fixture.Agent.PrepareCount);
        var competitor = request with { WorkflowId = Guid.NewGuid() };
        await Fault("ReservationConflict", () => fixture.Reservations.AcquireAsync(competitor));
    }

    [Fact]
    public async Task KnownPartialPrepareReleasesHoldsAndCentralReservation()
    {
        await using var fixture = await Fixture.CreateAsync();
        var first = await fixture.ServerAsync("one");
        var second = await fixture.ServerAsync("two");
        fixture.Agent.FailHost = new[] { first.HostId, second.HostId }.Max();
        var request = new ReservationAcquireRequest { WorkflowId = Guid.NewGuid(), ResourceKeys = [ReservationService.ServerKey(first.ServerId), ReservationService.ServerKey(second.ServerId)] };
        await Assert.ThrowsAsync<IOException>(() => fixture.Reservations.AcquireAsync(request));
        var lease = await fixture.Reservations.GetAsync(request.WorkflowId);
        Assert.Equal(ReservationStatus.Released, lease.Status);
        Assert.Empty(fixture.Agent.Holds);
        Assert.All(await fixture.Db.Reservations.ToArrayAsync(), x => Assert.Equal(ReservationStatus.Released, x.State));
        Assert.Single(lease.Fences);
    }

    [Fact]
    public async Task UnknownPartialPreparePersistsFenceAndCanResumeAfterInspection()
    {
        await using var fixture = await Fixture.CreateAsync();
        var first = await fixture.ServerAsync("one");
        var second = await fixture.ServerAsync("two");
        fixture.Agent.FailHost = new[] { first.HostId, second.HostId }.Max();
        fixture.Agent.SnapshotUnavailableAfterFailure = true;
        var request = new ReservationAcquireRequest { WorkflowId = Guid.NewGuid(), AdapterId = "16", RunReference = "run-1", ResourceKeys = [ReservationService.ServerKey(first.ServerId), ReservationService.ServerKey(second.ServerId)] };
        await Assert.ThrowsAsync<IOException>(() => fixture.Reservations.AcquireAsync(request));
        var lease = await fixture.Reservations.GetAsync(request.WorkflowId);
        Assert.Equal(ReservationStatus.RecoveryRequired, lease.Status);
        Assert.Single(lease.Fences);
        fixture.Agent.SnapshotUnavailableAfterFailure = false;
        fixture.Agent.FailHost = null;
        await fixture.Reservations.RecoverMaintenancePreparationAsync(request.WorkflowId, WorkflowRecoveryAction.Resume);
        lease = await fixture.Reservations.GetAsync(request.WorkflowId);
        Assert.Equal(ReservationStatus.Prepared, lease.Status);
        Assert.Equal(2, lease.Fences.Length);
    }

    [Fact]
    public async Task ReleasedLeaseRemainsReadableAfterResourceIsReused()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var request = new ReservationAcquireRequest { WorkflowId = Guid.NewGuid(), ResourceKeys = [ReservationService.ServerKey(server.ServerId)] };
        var first = await fixture.Reservations.AcquireAsync(request);
        await fixture.Reservations.ReleaseAsync(first.ReservationId, new GateReleaseRequest(first.WorkflowId, first.Fences, GateOutcome.Succeeded, ""));
        var second = await fixture.Reservations.AcquireAsync(request with { WorkflowId = Guid.NewGuid() });
        Assert.NotEqual(first.ReservationId, second.ReservationId);
        Assert.Equal(ReservationStatus.Released, (await fixture.Reservations.GetAsync(first.WorkflowId)).Status);
        Assert.Equal(first.ReservationId, (await fixture.Reservations.GetByReservationAsync(first.ReservationId)).ReservationId);
        await Fault("ReservationConflict", () => fixture.Reservations.AcquireAsync(request));
    }

    [Fact]
    public async Task CommandUsesPostHoldRevisionAndDuplicateRequestDoesNotDispatchAgain()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var request = new ServerOperationRequest { Kind = OperationKind.Start, ConfigRevision = 1, ExpectedStateRevision = 1 };
        var key = Guid.NewGuid();
        var operation = await fixture.Operations.CreateAsync(server.ServerId, request, key);
        Assert.Equal(OperationStatus.Accepted, operation.Status);
        var command = ControlJson.Deserialize<OperationCommand>(operation.CommandJson);
        Assert.Equal(2, command.ExpectedStateRevision);
        Assert.Equal(operation.CommandSha256, ControlProtocol.Hash(operation.CommandJson));
        Assert.Equal(1, fixture.Agent.DispatchCount);
        Assert.Equal(operation.OperationId, (await fixture.Operations.CreateAsync(server.ServerId, request, key)).OperationId);
        Assert.Equal(1, fixture.Agent.DispatchCount);
        await Fault("OperationConflict", () => fixture.Operations.CreateAsync(server.ServerId, request with { Kind = OperationKind.Stop }, key));
    }

    [Fact]
    public async Task QueuedWebOperationRedispatchesTheSameCommandAfterTransportReturns()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        fixture.Agent.DispatchUnavailable = true;
        var request = new ServerOperationRequest { Kind = OperationKind.Start, ConfigRevision = 1, ExpectedStateRevision = 1 };
        var key = Guid.NewGuid();
        var queued = await fixture.Operations.CreateAsync(server.ServerId, request, key);
        Assert.Equal(OperationStatus.Queued, queued.Status);
        fixture.Agent.DispatchUnavailable = false;
        var accepted = await fixture.Operations.RefreshAsync(queued.OperationId);
        Assert.Equal(OperationStatus.Accepted, accepted.Status);
        Assert.Equal(queued.OperationId, accepted.OperationId);
        Assert.Equal(queued.CommandSha256, accepted.CommandSha256);
    }

    [Fact]
    public async Task MaintenanceStartRequiresPersistedStartupWaitAndNeverAcceptsAdapter17()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var request = new ServerOperationRequest { Kind = OperationKind.Start, ConfigRevision = 1, ExpectedStateRevision = 2 };
        var maintenance = new ReservationAcquireRequest { WorkflowId = Guid.NewGuid(), AdapterId = "01", RunReference = "run-1", ResourceKeys = [ReservationService.ServerKey(server.ServerId)], ServerConditions = [new(server.ServerId, ProcessRequirement.Stopped)] };
        var lease = await fixture.Reservations.AcquireAsync(maintenance);
        var inherited = request with { WorkflowId = lease.WorkflowId, ReservationId = lease.ReservationId };
        await Fault("ReservationConflict", () => fixture.Operations.CreateAsync(server.ServerId, inherited, Guid.NewGuid()));
        await fixture.Reservations.ValidateAsync(lease.ReservationId, new GateValidateRequest(lease.WorkflowId, lease.Fences, "WaitForStartup", "run-1"));
        var state = fixture.Agent.States[server.HostId];
        inherited = inherited with { ExpectedStateRevision = state.StateRevision };
        var started = await fixture.Operations.CreateAsync(server.ServerId, inherited, Guid.NewGuid());
        Assert.Equal(OperationStatus.Accepted, started.Status);
        await Fault("ReservationConflict", () => fixture.Operations.CreateAsync(server.ServerId, inherited with { Kind = OperationKind.Stop }, Guid.NewGuid()));
    }

    [Fact]
    public async Task Adapter17CannotInheritProcessOperation()
    {
        await using var fixture = await Fixture.CreateAsync();
        var server = await fixture.ServerAsync("one");
        var lease = await fixture.Reservations.AcquireAsync(new ReservationAcquireRequest { WorkflowId = Guid.NewGuid(), AdapterId = "17", RunReference = "run-17", ResourceKeys = [ReservationService.ServerKey(server.ServerId)] });
        var request = new ServerOperationRequest { Kind = OperationKind.Start, ConfigRevision = 1, ExpectedStateRevision = fixture.Agent.States[server.HostId].StateRevision, WorkflowId = lease.WorkflowId, ReservationId = lease.ReservationId };
        await Fault("ReservationConflict", () => fixture.Operations.CreateAsync(server.ServerId, request, Guid.NewGuid()));
    }

    [Fact]
    public async Task SchemaOneMigrationBacksUpAndPreservesInventory()
    {
        var root = Path.Combine(Path.GetTempPath(), "control-migration-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        var path = Path.Combine(root, "control.db");
        try
        {
            var options = new DbContextOptionsBuilder<ControlDbContext>().UseSqlite($"Data Source={path};Pooling=False").Options;
            await using (var db = new ControlDbContext(options))
            {
                await ControlDatabase.InitializeAsync(db);
                db.Hosts.Add(new InventoryHost { HostId = Guid.NewGuid(), HostKey = "preserved", DisplayName = "Preserved" });
                await db.SaveChangesAsync();
                await db.Database.ExecuteSqlRawAsync("DROP TABLE Operations; DROP TABLE Reservations; DROP TABLE ReservationLeases; DROP TABLE Workflows; DROP TABLE CliCredentials; PRAGMA user_version=1;");
            }
            await using (var db = new ControlDbContext(options))
            {
                await ControlDatabase.InitializeAsync(db);
                Assert.Equal(1, await db.Hosts.CountAsync());
                Assert.Equal(0, await db.Operations.CountAsync());
                Assert.Equal(0, await db.CliCredentials.CountAsync());
                await db.Database.OpenConnectionAsync();
                await using var versionCommand = db.Database.GetDbConnection().CreateCommand();
                versionCommand.CommandText = "PRAGMA user_version;";
                Assert.Equal(2L, Convert.ToInt64(await versionCommand.ExecuteScalarAsync()));
                await db.Database.CloseConnectionAsync();
            }
            Assert.True(File.Exists(path + ".before-v2.bak"));
        }
        finally { Directory.Delete(root, true); }
    }

    private static async Task Fault(string code, Func<Task> action) => Assert.Equal(code, (await Assert.ThrowsAsync<InventoryFault>(action)).Code);

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly SqliteConnection connection = new("Data Source=:memory:");
        public ControlDbContext Db { get; private set; } = null!;
        public FakeAgent Agent { get; } = new();
        public ReservationService Reservations { get; private set; } = null!;
        public ControlOperationsService Operations { get; private set; } = null!;
        public static async Task<Fixture> CreateAsync()
        {
            var f = new Fixture(); await f.connection.OpenAsync();
            f.Db = new(new DbContextOptionsBuilder<ControlDbContext>().UseSqlite(f.connection).Options);
            await f.Db.Database.EnsureCreatedAsync();
            var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> { ["Control:EnableProcessControl"] = "true" }).Build();
            var store = new InventoryStore(f.Db, new InventoryWriteGate(), TimeProvider.System, config);
            f.Reservations = new(f.Db, store, f.Agent, config, TimeProvider.System);
            f.Operations = new(f.Db, store, f.Reservations, f.Agent, TimeProvider.System);
            return f;
        }
        public async Task<InventoryServer> ServerAsync(string key)
        {
            var hostId = Guid.NewGuid(); var id = Guid.NewGuid(); var environmentId = Guid.NewGuid();
            var config = new ServerConfig { ServerId = id, HostId = hostId, Revision = 1, ManagedRootId = Guid.NewGuid(), ExecutableId = Guid.NewGuid(), ServerDirectory = "servers/" + key, ListenPort = 25565 };
            Db.Hosts.Add(new InventoryHost { HostId = hostId, HostKey = key, DisplayName = key, Enabled = true, ConnectionState = HostConnectionState.Online, LastSeenAtUtc = DateTimeOffset.UtcNow, CapabilitiesJson = ControlJson.Serialize(new[] { "P1.Operations", "P1.LegacyGate" }) });
            Db.Environments.Add(new InventoryEnvironment { EnvironmentId = environmentId, EnvironmentKey = key, DisplayName = key });
            var server = new InventoryServer { ServerId = id, HostId = hostId, ServerKey = key, DisplayName = key, EnvironmentId = environmentId, DesiredRevision = 1, AppliedRevision = 1, DesiredConfigJson = ControlJson.Serialize(config) };
            Db.Servers.Add(server); await Db.SaveChangesAsync();
            Agent.States[hostId] = new ServerRuntimeSnapshot { ServerId = id, ConfigRevision = 1, StateRevision = 1, ProcessState = ProcessState.Stopped };
            return server;
        }
        public async ValueTask DisposeAsync() { await Db.DisposeAsync(); await connection.DisposeAsync(); }
    }

    private sealed class FakeAgent : IAgentOperationTransport
    {
        public Dictionary<Guid, ServerRuntimeSnapshot> States { get; } = [];
        public Dictionary<Guid, ResourceHold> Holds { get; } = [];
        public Guid? FailHost { get; set; }
        public bool SnapshotUnavailableAfterFailure { get; set; }
        public bool DispatchUnavailable { get; set; }
        public int PrepareCount { get; private set; }
        public int DispatchCount { get; private set; }
        public Task<RunnerSnapshot> SnapshotAsync(Guid hostId, CancellationToken token)
        {
            if (SnapshotUnavailableAfterFailure && hostId == FailHost && PrepareCount > 0) throw new IOException("Snapshot unavailable.");
            return Task.FromResult(new RunnerSnapshot { RunnerEpoch = Guid.NewGuid(), Servers = [States[hostId]], Holds = Holds.TryGetValue(hostId, out var hold) ? [hold] : [] });
        }
        public async Task<PreparedHold> PrepareAsync(Guid hostId, AcquireHoldRequest request, CancellationToken token)
        {
            PrepareCount++;
            if (hostId == FailHost) throw new IOException("Injected lost response.");
            var hold = new ResourceHold { ReservationId = request.ReservationId, OwnerWorkflowId = request.OwnerWorkflowId, ResourceKeys = request.ResourceKeys, Mode = request.Mode, Fence = 1 };
            Holds[hostId] = hold;
            States[hostId] = States[hostId] with { StateRevision = States[hostId].StateRevision + 1 };
            return new(hold, await SnapshotAsync(hostId, token));
        }
        public Task ReleaseAsync(Guid hostId, ReleaseHoldRequest request, CancellationToken token) { Holds.Remove(hostId); return Task.CompletedTask; }
        public Task<ExecutionSnapshot> DispatchAsync(Guid hostId, Guid operationId, string json, string hash, CancellationToken token)
        {
            DispatchCount++;
            if (DispatchUnavailable) throw new IOException("Transport unavailable.");
            return Task.FromResult(new ExecutionSnapshot { OperationId = operationId, CommandSha256 = hash, Status = OperationStatus.Accepted, StatusVersion = 1, UpdatedAtUtc = DateTimeOffset.UtcNow });
        }
        public Task<ExecutionSnapshot> QueryAsync(Guid hostId, Guid operationId, CancellationToken token) => throw new NotImplementedException();
        public Task<LogBatch> ReadLogsAsync(Guid hostId, ReadLogsRequest request, CancellationToken token) => throw new NotImplementedException();
        public Task<RecoveryInspection> InspectRecoveryAsync(Guid hostId, Guid serverId, CancellationToken token) => throw new NotImplementedException();
    }
}
