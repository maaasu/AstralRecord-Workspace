using System.Text;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Operations;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace AstralRecordControl.Operations.Tests;

public sealed class BackupProfileTests
{
    [Fact]
    public async Task ProfilesRequireAckAndSurviveServerUpdatesWithOptimisticRevision()
    {
        await using var fixture = await Fixture.CreateAsync();
        var profile = fixture.Profile;
        var key = Guid.NewGuid();
        await fixture.Store.SaveBackupProfileAsync(profile, null, key);
        await fixture.Store.SaveBackupProfileAsync(profile, null, key);
        Assert.Single(await fixture.Store.BackupProfilesAsync());
        await Fault("StaleRevision", () => fixture.Store.AppliedBackupProfileAsync(profile.ServerId, profile.BackupProfileId));
        fixture.Host.AppliedInventoryRevision = fixture.Host.DesiredInventoryRevision;
        await fixture.Db.SaveChangesAsync();
        Assert.Equal(ControlJson.Serialize(profile), ControlJson.Serialize(await fixture.Store.AppliedBackupProfileAsync(profile.ServerId, profile.BackupProfileId)));
        var next = fixture.Config with { Revision = 2 };
        var update = new ServerUpdateRequest("updated", null, null, next);
        await fixture.Store.UpdateServerAsync(next.ServerId, update, 1, ControlJson.Serialize(next));
        Assert.Equal(ControlJson.Serialize(profile), ControlJson.Serialize(Assert.Single(await fixture.Store.BackupProfilesAsync())));
        await Fault("StaleRevision", () => fixture.Store.SaveBackupProfileAsync(profile with { Revision = 2 }, 9, Guid.NewGuid()));
        await fixture.Store.SaveBackupProfileAsync(profile with { Revision = 2, RetentionDays = 31 }, 1, Guid.NewGuid());
        await Fault("StaleRevision", () => fixture.Store.AppliedBackupProfileAsync(profile.ServerId, profile.BackupProfileId, 2));
        Assert.Equal(31, Assert.Single(await fixture.Store.BackupProfilesAsync()).RetentionDays);
    }

    [Fact]
    public async Task ProfileCannotMoveServersOrChangeWhileReserved()
    {
        await using var fixture = await Fixture.CreateAsync();
        await fixture.Store.SaveBackupProfileAsync(fixture.Profile, null, Guid.NewGuid());
        var lease = Guid.NewGuid();
        fixture.Db.Reservations.Add(new ControlReservation { ResourceKey = ReservationService.ServerKey(fixture.Config.ServerId), ReservationId = lease, OwnerWorkflowId = Guid.NewGuid(), State = ReservationStatus.Prepared });
        await fixture.Db.SaveChangesAsync();
        await Fault("ReservationConflict", () => fixture.Store.SaveBackupProfileAsync(fixture.Profile with { Revision = 2 }, 1, Guid.NewGuid()));
    }

    [Fact]
    public async Task LargeResultUploadChecksHostCommandHashAndImmutableContent()
    {
        await using var fixture = await Fixture.CreateAsync();
        var workflow = new ControlWorkflow { WorkflowId = Guid.NewGuid() };
        fixture.Db.Workflows.Add(workflow);
        var operation = new ControlOperation { OperationId = Guid.NewGuid(), IdempotencyKey = Guid.NewGuid(), HostId = fixture.Host.HostId, ServerId = fixture.Config.ServerId, WorkflowId = workflow.WorkflowId, Kind = OperationKind.ReadFile, CommandSha256 = ControlProtocol.Hash("command"), Status = OperationStatus.Running };
        fixture.Db.Operations.Add(operation); await fixture.Db.SaveChangesAsync();
        var store = new OperationResultStore(fixture.Configuration, fixture.Db);
        var json = ControlJson.Serialize(new { contentUtf8 = new string('x', 2 * 1024 * 1024), encoding = "utf-8", etag = ControlProtocol.Hash("x") });
        await Fault("HostScopeMismatch", () => Save(Guid.NewGuid(), operation.CommandSha256, json));
        await Fault("OperationConflict", () => Save(fixture.Host.HostId, ControlProtocol.Hash("other"), json));
        await Save(fixture.Host.HostId, operation.CommandSha256, json);
        await Save(fixture.Host.HostId, operation.CommandSha256, json);
        Assert.Equal(json, await store.ReadAsync(operation.OperationId));
        await Fault("OperationConflict", () => Save(fixture.Host.HostId, operation.CommandSha256, "{}"));
        async Task Save(Guid host, string commandHash, string value)
        {
            using var input = new MemoryStream(Encoding.UTF8.GetBytes(value));
            await store.SaveAsync(host, operation.OperationId, commandHash, ControlProtocol.Hash(value), input, CancellationToken.None);
        }
    }

    private static async Task Fault(string code, Func<Task> action) => Assert.Equal(code, (await Assert.ThrowsAsync<InventoryFault>(action)).Code);

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly string root = Path.Combine(Path.GetTempPath(), "control-p2-web-" + Guid.NewGuid().ToString("N"));
        private readonly SqliteConnection connection = new("Data Source=:memory:");
        public ControlDbContext Db { get; private set; } = null!;
        public InventoryStore Store { get; private set; } = null!;
        public InventoryHost Host { get; private set; } = null!;
        public ServerConfig Config { get; private set; } = null!;
        public BackupProfile Profile { get; private set; } = null!;
        public IConfiguration Configuration { get; private set; } = null!;
        public static async Task<Fixture> CreateAsync()
        {
            var f = new Fixture(); await f.connection.OpenAsync();
            f.Db = new ControlDbContext(new DbContextOptionsBuilder<ControlDbContext>().UseSqlite(f.connection).Options);
            await f.Db.Database.EnsureCreatedAsync();
            f.Configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> { ["Control:DataRoot"] = f.root, ["Control:EnableProcessControl"] = "true", ["Control:EnableFileControl"] = "true" }).Build();
            f.Store = new InventoryStore(f.Db, new InventoryWriteGate(), TimeProvider.System, f.Configuration);
            f.Host = await f.Store.CreateHostAsync(new("fixture", "Fixture"), Guid.NewGuid(), "fixture");
            var managedRoot = Guid.NewGuid(); var executable = Guid.NewGuid(); var destination = Guid.NewGuid();
            f.Host.ResourcesJson = ControlJson.Serialize(new HostResources { Roots = [new() { Id = managedRoot }, new() { Id = destination, BackupOnly = true }], Executables = [new() { Id = executable, Kind = ServerKind.Java }] });
            await f.Db.SaveChangesAsync();
            f.Config = new ServerConfig { ServerId = Guid.NewGuid(), HostId = f.Host.HostId, Revision = 1, ManagedRootId = managedRoot, ExecutableId = executable, ServerDirectory = "dev", Kind = ServerKind.Java };
            var create = new ServerCreateRequest(f.Host.HostId, "dev", "Dev", null, null, f.Config);
            await f.Store.CreateServerAsync(create, Guid.NewGuid(), ControlJson.Serialize(create), ControlJson.Serialize(f.Config));
            f.Profile = new BackupProfile { BackupProfileId = Guid.NewGuid(), ServerId = f.Config.ServerId, Revision = 1, DestinationRootId = destination };
            return f;
        }
        public async ValueTask DisposeAsync() { await Db.DisposeAsync(); await connection.DisposeAsync(); if (Directory.Exists(root)) Directory.Delete(root, true); }
    }
}
