using System.Text.Json;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordControl.Inventory.Tests;

public sealed class InventoryStateTests
{
    [Fact]
    public async Task IdempotencyIsGlobalAndInputSensitive()
    {
        await using var f = await Fixture.CreateAsync();
        var key = Guid.NewGuid(); var body = new HostCreateRequest("dev-pc", "Dev PC"); var json = ControlJson.Serialize(body);
        var first = await f.Store.CreateHostAsync(body, key, json);
        var replay = await f.Store.CreateHostAsync(body, key, json);
        Assert.Equal(first.HostId, replay.HostId);
        Assert.Equal(1, await f.Db.Hosts.CountAsync());
        await Fault("OperationConflict", () => f.Store.CreateHostAsync(body with { DisplayName = "changed" }, key, json + " "));
        await Fault("OperationConflict", () => f.Store.CreateEnvironmentAsync(new("shared", "Shared"), key, json));
        await Fault("OperationConflict", () => f.Store.CreateHostAsync(body, Guid.NewGuid(), json));
    }

    [Fact]
    public async Task RevisionsSerializeCompetingWriters()
    {
        await using var f = await Fixture.CreateAsync(); var host = await f.HostAsync("dev-pc");
        var options = new DbContextOptionsBuilder<ControlDbContext>().UseSqlite(f.Connection).Options;
        await using var db2 = new ControlDbContext(options);
        var second = new InventoryStore(db2, f.Gate, f.Clock);
        var results = await Task.WhenAll(Attempt(f.Store), Attempt(second));
        Assert.Single(results, x => x); Assert.Single(results, x => !x);
        f.Db.ChangeTracker.Clear(); Assert.Equal(2, (await f.Store.HostAsync(host.HostId)).Revision);
        async Task<bool> Attempt(InventoryStore store)
        {
            try { await store.UpdateHostAsync(host.HostId, new("Updated", true), 1); return true; }
            catch (InventoryFault fault) { Assert.Equal("StaleRevision", fault.Code); return false; }
        }
    }

    [Fact]
    public async Task SnapshotApplicationKeepsOldRevisionAfterFailureAndOriginalBytes()
    {
        await using var f = await Fixture.CreateAsync(); var host = await f.HostAsync("dev-pc"); var hello = f.Hello();
        var ack = await f.Protocol.HelloAsync(f.Identity(host), hello, "c1", () => { });
        Assert.Equal(HostConnectionState.Reconciling, host.ConnectionState);
        await Fault("StateConflict", () => f.Protocol.HeartbeatAsync(f.Identity(host), f.Heartbeat(ack, hello), "c1"));
        host = await f.Store.HostAsync(host.HostId);
        await f.Protocol.ReconcileAsync(f.Identity(host), new() { ConnectionEpoch = ack.ConnectionEpoch, RunnerEpoch = hello.RunnerEpoch }, "c1");
        var envelope = await f.Store.InventoryAsync(host.HostId);
        await f.Protocol.ApplyAckAsync(f.Identity(host), new(ack.ConnectionEpoch, envelope.Revision, envelope.Sha256, true), "c1");
        var config = f.Config(host, hello); var raw = " \n" + ControlJson.Serialize(config) + "\n ";
        var request = new ServerCreateRequest(host.HostId, "dev", "Dev", null, null, config);
        var server = await f.Store.CreateServerAsync(request, Guid.NewGuid(), ControlJson.Serialize(request), raw);
        var next = await f.Store.InventoryAsync(host.HostId);
        Assert.Equal(raw, server.DesiredConfigJson); Assert.Equal(ControlProtocol.Hash(raw), server.ConfigSha256);
        Assert.Equal(ControlProtocol.Hash(next.SnapshotJson), next.Sha256);
        await f.Protocol.ApplyAckAsync(f.Identity(host), new(ack.ConnectionEpoch, next.Revision, next.Sha256, false, "PathOutsideRoot"), "c1");
        Assert.Equal(envelope.Revision, host.AppliedInventoryRevision); Assert.Equal(0, server.AppliedRevision); Assert.Equal("PathOutsideRoot", host.InventoryErrorCode);
        await Fault("HashMismatch", () => f.Protocol.ApplyAckAsync(f.Identity(host), new(ack.ConnectionEpoch, next.Revision, new string('0', 64), true), "c1"));
        host = await f.Store.HostAsync(host.HostId);
        await f.Protocol.ApplyAckAsync(f.Identity(host), new(ack.ConnectionEpoch, next.Revision, next.Sha256, true), "c1");
        Assert.Equal(next.Revision, host.AppliedInventoryRevision);
        Assert.Equal(1, (await f.Store.ServerAsync(config.ServerId)).AppliedRevision);
        Assert.Equal(envelope.SnapshotJson, (await f.Db.InventoryVersions.SingleAsync(v => v.HostId == host.HostId && v.Revision == envelope.Revision)).SnapshotJson);
    }

    [Fact]
    public async Task StaleConnectionAndOtherHostRuntimeAreRejected()
    {
        await using var f = await Fixture.CreateAsync(); var host = await f.HostAsync("dev-pc"); var other = await f.HostAsync("mc-pc"); var hello = f.Hello(); var aborted = 0;
        var first = await f.Protocol.HelloAsync(f.Identity(host), hello, "old", () => aborted++);
        var next = await f.Protocol.HelloAsync(f.Identity(host), hello, "new", () => { });
        Assert.Equal(1, aborted); Assert.NotEqual(first.ConnectionEpoch, next.ConnectionEpoch);
        await Fault("StateConflict", () => f.Protocol.ReconcileAsync(f.Identity(host), new() { ConnectionEpoch = first.ConnectionEpoch, RunnerEpoch = hello.RunnerEpoch }, "old"));
        await Fault("StateConflict", () => f.Protocol.ReconcileAsync(f.Identity(other), new() { ConnectionEpoch = next.ConnectionEpoch, RunnerEpoch = hello.RunnerEpoch }, "new"));
        await f.Protocol.ReconcileAsync(f.Identity(host), new() { ConnectionEpoch = next.ConnectionEpoch, RunnerEpoch = hello.RunnerEpoch }, "new");
        await f.Protocol.DisconnectedAsync(f.Identity(host), "old");
        Assert.NotNull(f.Connections.Current(host.HostId));
        await Fault("HostScopeMismatch", () => f.Protocol.HeartbeatAsync(f.Identity(host), f.Heartbeat(next, hello) with { ServerSnapshots = [new() { ServerId = Guid.NewGuid() }] }, "new"));
    }

    [Fact]
    public async Task EventBatchesAreAtomicContiguousAndContentSensitive()
    {
        await using var f = await Fixture.CreateAsync(); var host = await f.HostAsync("dev-pc"); var hello = f.Hello(); var ack = await f.Protocol.HelloAsync(f.Identity(host), hello, "c", () => { });
        await f.Protocol.ReconcileAsync(f.Identity(host), new() { ConnectionEpoch = ack.ConnectionEpoch, RunnerEpoch = hello.RunnerEpoch }, "c");
        var e = new AgentEvent(1, null, null, "Diagnostic", f.Clock.GetUtcNow(), ControlJson.Element(new { state = "ok" }));
        Assert.Equal(1, (await f.Protocol.PublishEventsAsync(f.Identity(host), new(ack.ConnectionEpoch, [e]), "c")).AckEventSeq);
        Assert.Equal(1, (await f.Protocol.PublishEventsAsync(f.Identity(host), new(ack.ConnectionEpoch, [e]), "c")).AckEventSeq);
        await Fault("OperationConflict", () => f.Protocol.PublishEventsAsync(f.Identity(host), new(ack.ConnectionEpoch, [e with { Body = ControlJson.Element(new { state = "changed" }) }]), "c"));
        await Fault("StateConflict", () => f.Protocol.PublishEventsAsync(f.Identity(host), new(ack.ConnectionEpoch, [e with { EventSeq = 2 }, e with { EventSeq = 4 }]), "c"));
        Assert.Equal(1, (await f.Store.HostAsync(host.HostId)).AckAgentEventSeq); Assert.Equal(1, await f.Db.AgentEvents.CountAsync());
    }

    [Fact]
    public async Task StoreRollbackAndUnknownProtocolRequireExplicitRecovery()
    {
        await using var f = await Fixture.CreateAsync(); var host = await f.HostAsync("dev-pc"); var hello = f.Hello();
        await Fault("ProtocolMismatch", () => f.Protocol.HelloAsync(f.Identity(host), hello with { ProtocolVersions = [2] }, "bad", () => { }));
        Assert.Equal(HostConnectionState.Incompatible, (await f.Store.HostAsync(host.HostId)).ConnectionState);
        var ack = await f.Protocol.HelloAsync(f.Identity(host), hello, "good", () => { });
        await f.Protocol.ReconcileAsync(f.Identity(host), new() { ConnectionEpoch = ack.ConnectionEpoch, RunnerEpoch = hello.RunnerEpoch }, "good");
        var e = new AgentEvent(1, null, null, "Diagnostic", f.Clock.GetUtcNow(), ControlJson.Element(new { }));
        await f.Protocol.PublishEventsAsync(f.Identity(host), new(ack.ConnectionEpoch, [e]), "good");
        await Fault("RecoveryRequired", () => f.Protocol.HelloAsync(f.Identity(host), hello with { MaxLocalEventSeq = 0 }, "rollback", () => { }));
        Assert.True((await f.Store.HostAsync(host.HostId)).RecoveryRequired); Assert.Null(f.Connections.Current(host.HostId));
        await Fault("RecoveryRequired", () => f.Protocol.HelloAsync(f.Identity(host), hello with { MaxLocalEventSeq = 1 }, "retry", () => { }));
    }

    [Fact]
    public async Task RevokingCredentialDisconnectsImmediatelyAndStoresOnlyHash()
    {
        await using var f = await Fixture.CreateAsync(); var host = await f.HostAsync("dev-pc"); var issued = await f.Credentials.IssueAsync(host.HostId);
        var identity = await f.Credentials.AuthenticateAsync(issued.Token); Assert.NotNull(identity);
        var row = await f.Db.AgentCredentials.SingleAsync(x => x.CredentialId == issued.CredentialId);
        Assert.NotEqual(issued.Token, row.TokenHashHex); Assert.Equal(ControlProtocol.Hash(issued.Token), row.TokenHashHex);
        var aborted = 0; var ack = await f.Protocol.HelloAsync(identity!, f.Hello(), "c", () => aborted++);
        await f.Credentials.RevokeAsync(host.HostId, issued.CredentialId);
        Assert.Equal(1, aborted); Assert.Null(await f.Credentials.AuthenticateAsync(issued.Token)); Assert.Null(f.Connections.Current(host.HostId));
        await Fault("AgentDisabled", () => f.Protocol.RequireAsync(identity!, ack.ConnectionEpoch));
    }

    [Fact]
    public async Task LongSilenceRequiresNewHelloButDoesNotLoseTheStoreIdentity()
    {
        await using var f = await Fixture.CreateAsync(); var host = await f.HostAsync("dev-pc"); var hello = f.Hello(); var ack = await f.Protocol.HelloAsync(f.Identity(host), hello, "first", () => { });
        await f.Protocol.ReconcileAsync(f.Identity(host), new() { ConnectionEpoch = ack.ConnectionEpoch, RunnerEpoch = hello.RunnerEpoch }, "first");
        f.Clock.Advance(TimeSpan.FromHours(3));
        await Fault("HostOffline", () => f.Protocol.HeartbeatAsync(f.Identity(host), f.Heartbeat(ack, hello), "first"));
        var next = await f.Protocol.HelloAsync(f.Identity(host), hello, "again", () => { });
        Assert.NotEqual(ack.ConnectionEpoch, next.ConnectionEpoch);
        await f.Protocol.ReconcileAsync(f.Identity(host), new() { ConnectionEpoch = next.ConnectionEpoch, RunnerEpoch = hello.RunnerEpoch }, "again");
        Assert.Equal(HostConnectionState.Online, (await f.Store.HostAsync(host.HostId)).ConnectionState);
    }

    [Fact]
    public async Task OtherHostTransferIsForbiddenBeforeP0CapabilityRejection()
    {
        await using var f = await Fixture.CreateAsync(); var source = await f.HostAsync("dev-pc"); var destination = await f.HostAsync("mc-pc"); var stranger = await f.HostAsync("other-pc");
        var id = Guid.NewGuid(); f.Db.TransferScopes.Add(new() { TransferId = id, SourceHostId = source.HostId, DestinationHostId = destination.HostId }); await f.Db.SaveChangesAsync();
        await Fault("HostScopeMismatch", () => f.Store.ValidateTransferScopeAsync(stranger.HostId, id, "content"));
        await Fault("HostScopeMismatch", () => f.Store.ValidateTransferScopeAsync(source.HostId, id, "content"));
        await Fault("HostScopeMismatch", () => f.Store.ValidateTransferScopeAsync(destination.HostId, id, "prepare"));
        await Fault("UnsupportedCapability", () => f.Store.ValidateTransferScopeAsync(destination.HostId, id, "content"));
        await Fault("UnsupportedCapability", () => f.Store.ValidateTransferScopeAsync(source.HostId, id, "prepare"));
    }

    [Fact]
    public async Task OnlyAgentReportedResourcesAndPassiveP0SettingsCanBeRegistered()
    {
        await using var f = await Fixture.CreateAsync(); var host = await f.HostAsync("mc-pc"); var hello = f.Hello(); await f.Protocol.HelloAsync(f.Identity(host), hello, "c", () => { });
        var config = f.Config(host, hello); var body = new ServerCreateRequest(host.HostId, "channel1", "Channel 1", null, null, config);
        await Fault("PathOutsideRoot", () => Create(config with { ManagedRootId = Guid.NewGuid() }));
        await Fault("UnsupportedCapability", () => Create(config with { AutoStart = true }));
        await Fault("UnsupportedCapability", () => Create(config with { RestartOnCrash = true }));
        var first = await Create(config);
        var second = config with { ServerId = Guid.NewGuid() };
        await Assert.ThrowsAsync<ArgumentException>(() => f.Store.CreateServerAsync(body with { ServerKey = "channel2", Config = second }, Guid.NewGuid(), "second", ControlJson.Serialize(second)));
        Assert.Equal(1, await f.Db.Servers.CountAsync());
        async Task<InventoryServer> Create(ServerConfig value) => await f.Store.CreateServerAsync(body with { Config = value }, Guid.NewGuid(), ControlJson.Serialize(value), ControlJson.Serialize(value));
    }

    [Fact]
    public async Task DevAndChannelsCanShareExplicitEnvironmentAcrossDifferentHosts()
    {
        await using var f = await Fixture.CreateAsync();
        var environment = await f.Store.CreateEnvironmentAsync(new("shared-runtime", "Shared API/master"), Guid.NewGuid(), "shared");
        foreach (var hostKey in new[] { "dev-pc", "mc-pc" })
        {
            var host = await f.HostAsync(hostKey); var hello = f.Hello();
            await f.Protocol.HelloAsync(f.Identity(host), hello, hostKey, () => { });
            var keys = hostKey == "dev-pc" ? new[] { "dev" } : new[] { "channel1", "channel2" };
            foreach (var (key, index) in keys.Select((key, index) => (key, index)))
            {
                var config = f.Config(host, hello) with { ServerDirectory = "servers/" + key, ListenPort = 25565 + index };
                var request = new ServerCreateRequest(host.HostId, key, key, environment.EnvironmentId, key, config);
                await f.Store.CreateServerAsync(request, Guid.NewGuid(), ControlJson.Serialize(request), ControlJson.Serialize(config));
            }
        }
        var servers = await f.Db.Servers.ToArrayAsync();
        Assert.Equal(3, servers.Length); Assert.All(servers, x => Assert.Equal(environment.EnvironmentId, x.EnvironmentId));
        Assert.Equal(2, servers.Select(x => x.HostId).Distinct().Count());
    }

    [Fact]
    public async Task RuntimeProjectionCannotMoveBackwardsWithinRunnerEpoch()
    {
        await using var f = await Fixture.CreateAsync(); var host = await f.HostAsync("dev-pc"); var hello = f.Hello(); var ack = await f.Protocol.HelloAsync(f.Identity(host), hello, "c", () => { });
        var config = f.Config(host, hello); var request = new ServerCreateRequest(host.HostId, "dev", "Dev", null, null, config);
        await f.Store.CreateServerAsync(request, Guid.NewGuid(), "server", ControlJson.Serialize(config));
        var current = new ServerRuntimeSnapshot { ServerId = config.ServerId, ConfigRevision = 1, StateRevision = 2, ProcessState = ProcessState.Stopped };
        await f.Protocol.ReconcileAsync(f.Identity(host), new() { ConnectionEpoch = ack.ConnectionEpoch, RunnerEpoch = hello.RunnerEpoch, Servers = [current] }, "c");
        await Fault("StateConflict", () => f.Protocol.HeartbeatAsync(f.Identity(host), f.Heartbeat(ack, hello) with { ServerSnapshots = [current with { StateRevision = 1 }] }, "c"));
        await Fault("StateConflict", () => f.Protocol.HeartbeatAsync(f.Identity(host), f.Heartbeat(ack, hello) with { ServerSnapshots = [current with { ProcessState = ProcessState.Running }] }, "c"));
        Assert.Equal(2, ControlJson.Deserialize<ServerRuntimeSnapshot[]>((await f.Store.HostAsync(host.HostId)).RuntimeSnapshotJson)[0].StateRevision);
    }

    private static async Task Fault(string code, Func<Task> action) => Assert.Equal(code, (await Assert.ThrowsAsync<InventoryFault>(action)).Code);

    private sealed class FakeClock : TimeProvider
    {
        private DateTimeOffset value = DateTimeOffset.Parse("2026-10-18T00:00:00Z");
        public override DateTimeOffset GetUtcNow() => value;
        public void Advance(TimeSpan time) => value += time;
    }
    private sealed class Fixture : IAsyncDisposable
    {
        public SqliteConnection Connection { get; } = new("Data Source=:memory:");
        public ControlDbContext Db { get; private set; } = null!;
        public InventoryWriteGate Gate { get; } = new();
        public FakeClock Clock { get; } = new();
        public AgentConnectionRegistry Connections { get; } = new();
        public InventoryStore Store { get; private set; } = null!;
        public AgentCredentialService Credentials { get; private set; } = null!;
        public AgentProtocolService Protocol { get; private set; } = null!;
        private readonly Dictionary<Guid, AgentIdentity> identities = [];
        public static async Task<Fixture> CreateAsync()
        {
            var fixture = new Fixture(); await fixture.Connection.OpenAsync();
            fixture.Db = new(new DbContextOptionsBuilder<ControlDbContext>().UseSqlite(fixture.Connection).Options);
            await fixture.Db.Database.EnsureCreatedAsync();
            fixture.Store = new(fixture.Db, fixture.Gate, fixture.Clock); fixture.Credentials = new(fixture.Db, fixture.Store, fixture.Connections, fixture.Clock);
            fixture.Protocol = new(fixture.Db, fixture.Store, fixture.Credentials, fixture.Connections, fixture.Clock); return fixture;
        }
        public async Task<InventoryHost> HostAsync(string key)
        {
            var request = new HostCreateRequest(key, key); var host = await Store.CreateHostAsync(request, Guid.NewGuid(), ControlJson.Serialize(request));
            var issued = await Credentials.IssueAsync(host.HostId); identities[host.HostId] = new(host.HostId, issued.CredentialId); return host;
        }
        public AgentIdentity Identity(InventoryHost host) => identities[host.HostId];
        public AgentHello Hello() => new() { AgentVersion = "test", AgentStoreId = Guid.NewGuid(), RunnerEpoch = Guid.NewGuid(), LocalResources = new() { Roots = [new() { Id = Guid.NewGuid(), DisplayName = "MC", Path = "C:\\MC" }], Executables = [new() { Id = Guid.NewGuid(), DisplayName = "Java", Path = "C:\\Java\\java.exe", Kind = ServerKind.Java }] } };
        public ServerConfig Config(InventoryHost host, AgentHello hello) => new() { ServerId = Guid.NewGuid(), HostId = host.HostId, Revision = 1, ManagedRootId = hello.LocalResources.Roots[0].Id, ExecutableId = hello.LocalResources.Executables[0].Id, ServerDirectory = "servers/dev", ListenPort = 25565 };
        public AgentHeartbeat Heartbeat(HelloAck ack, AgentHello hello) => new() { ConnectionEpoch = ack.ConnectionEpoch, RunnerEpoch = hello.RunnerEpoch, ObservedAtUtc = Clock.GetUtcNow(), RunnerAvailable = true };
        public async ValueTask DisposeAsync() { await Db.DisposeAsync(); await Connection.DisposeAsync(); }
    }
}
