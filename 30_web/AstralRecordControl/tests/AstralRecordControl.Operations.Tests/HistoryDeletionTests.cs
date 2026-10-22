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

public sealed class HistoryDeletionTests
{
    [Fact]
    public async Task HidingTerminalHistoryRemovesResultFileButRetainsReplayIdentity()
    {
        await using var fixture = await Fixture.CreateAsync();
        var (workflow, operation, _) = await fixture.AddAsync();
        var result = "{}";
        await using (var source = new MemoryStream(Encoding.UTF8.GetBytes(result)))
            await fixture.Results.SaveAsync(fixture.HostId, operation.OperationId, operation.CommandSha256, ControlProtocol.Hash(result), source, CancellationToken.None);
        var path = fixture.ResultPath(operation.OperationId);
        Assert.True(File.Exists(path));

        Assert.Equal(1, await fixture.Deletion.DeleteAsync([workflow.WorkflowId]));
        Assert.Equal(0, await fixture.Deletion.DeleteAsync([workflow.WorkflowId]));
        Assert.False(File.Exists(path));
        Assert.NotNull(workflow.HiddenAtUtc);
        Assert.Equal("", operation.CommandJson);
        Assert.Null(operation.ResultJson);
        Assert.NotEqual("", operation.RequestSha256);
        Assert.Equal(operation.IdempotencyKey, (await fixture.Db.Operations.SingleAsync()).IdempotencyKey);
        await using var replay = new MemoryStream(Encoding.UTF8.GetBytes(result));
        Assert.Equal("HistoryExpired", (await Assert.ThrowsAsync<InventoryFault>(() => fixture.Results.SaveAsync(fixture.HostId, operation.OperationId, operation.CommandSha256, ControlProtocol.Hash(result), replay, CancellationToken.None))).Code);
        Assert.Equal("NotFound", (await Assert.ThrowsAsync<InventoryFault>(() => fixture.Results.ReadAsync(operation.OperationId))).Code);
        var replayed = await new ControlOperationsService(fixture.Db, fixture.Store, null!, null!, TimeProvider.System)
            .ApplyExecutionAsync(operation.OperationId, new ExecutionSnapshot
            {
                OperationId = operation.OperationId, CommandSha256 = operation.CommandSha256,
                Status = OperationStatus.Succeeded, StatusVersion = operation.StatusVersion + 1,
                UpdatedAtUtc = DateTimeOffset.UtcNow, Result = ControlJson.Element(new { replay = true })
            });
        Assert.Null(replayed.ResultJson);
        Assert.Equal(operation.StatusVersion, replayed.StatusVersion);
    }

    [Fact]
    public async Task BatchRejectsActiveOrRecoveryHistoryWithoutHidingAnySelection()
    {
        await using var fixture = await Fixture.CreateAsync();
        var first = await fixture.AddAsync();
        var second = await fixture.AddAsync();
        second.Item3.Status = ReservationStatus.RecoveryRequired;
        await fixture.Db.SaveChangesAsync();
        Assert.False(ControlHistoryDeletionService.CanDelete(second.Item1, second.Item3, [second.Item2], []));
        Assert.Equal("HistoryInUse", (await Assert.ThrowsAsync<InventoryFault>(() => fixture.Deletion.DeleteAsync([first.Item1.WorkflowId, second.Item1.WorkflowId]))).Code);
        Assert.Null(first.Item1.HiddenAtUtc);
        Assert.NotEqual("", first.Item2.CommandJson);
        second.Item3.Status = ReservationStatus.Released;
        fixture.Db.Reservations.Add(new ControlReservation { ResourceKey = "hold", OwnerWorkflowId = second.Item1.WorkflowId, ReservationId = second.Item3.ReservationId, State = ReservationStatus.Prepared });
        await fixture.Db.SaveChangesAsync();
        Assert.Equal("HistoryInUse", (await Assert.ThrowsAsync<InventoryFault>(() => fixture.Deletion.DeleteAsync([second.Item1.WorkflowId]))).Code);
    }

    [Fact]
    public async Task RetentionRetriesFileCleanupAfterCommittedHide()
    {
        await using var fixture = await Fixture.CreateAsync();
        var (workflow, operation, _) = await fixture.AddAsync();
        workflow.HiddenAtUtc = DateTimeOffset.UtcNow;
        await fixture.Db.SaveChangesAsync();
        var path = fixture.ResultPath(operation.OperationId);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        await File.WriteAllTextAsync(path, "{}");
        await new ControlHistoryRetention(fixture.Db, fixture.Store, fixture.Results, TimeProvider.System).RunAsync();
        Assert.False(File.Exists(path));
    }

    [Fact]
    public async Task SchemaFourMigrationPreservesWorkflowAndAddsHiddenMarker()
    {
        var root = Path.Combine(Path.GetTempPath(), "control-history-schema-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        var path = Path.Combine(root, "control.db");
        var options = new DbContextOptionsBuilder<ControlDbContext>().UseSqlite($"Data Source={path};Pooling=False").Options;
        var workflowId = Guid.NewGuid();
        try
        {
            await using (var db = new ControlDbContext(options))
            {
                await ControlDatabase.InitializeAsync(db);
                db.Workflows.Add(new ControlWorkflow { WorkflowId = workflowId, Status = WorkflowStatus.Succeeded, CreatedAtUtc = DateTimeOffset.UtcNow, UpdatedAtUtc = DateTimeOffset.UtcNow });
                await db.SaveChangesAsync();
                await db.Database.ExecuteSqlRawAsync("ALTER TABLE Workflows DROP COLUMN HiddenAtUtc; PRAGMA user_version=4;");
            }
            await using (var db = new ControlDbContext(options))
            {
                await ControlDatabase.InitializeAsync(db);
                Assert.Null((await db.Workflows.SingleAsync(w => w.WorkflowId == workflowId)).HiddenAtUtc);
            }
            Assert.Single(Directory.GetFiles(root, "control.db.before-v5.*.bak"));
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public async Task OneLockedResultDoesNotPreventOtherBatchFilesFromBeingRemoved()
    {
        await using var fixture = await Fixture.CreateAsync();
        var first = await fixture.AddAsync();
        var second = await fixture.AddAsync();
        var lockedPath = fixture.ResultPath(first.Item2.OperationId);
        var otherPath = fixture.ResultPath(second.Item2.OperationId);
        Directory.CreateDirectory(Path.GetDirectoryName(lockedPath)!);
        await File.WriteAllTextAsync(lockedPath, "{}");
        await File.WriteAllTextAsync(otherPath, "{}");
        File.SetAttributes(lockedPath, FileAttributes.ReadOnly);
        try
        {
            Assert.Equal("HistoryCleanupPending", (await Assert.ThrowsAsync<InventoryFault>(() => fixture.Deletion.DeleteAsync([first.Item1.WorkflowId, second.Item1.WorkflowId]))).Code);
            Assert.NotNull(first.Item1.HiddenAtUtc);
            Assert.NotNull(second.Item1.HiddenAtUtc);
            Assert.True(File.Exists(lockedPath));
            Assert.False(File.Exists(otherPath));
        }
        finally { File.SetAttributes(lockedPath, FileAttributes.Normal); }
        await new ControlHistoryRetention(fixture.Db, fixture.Store, fixture.Results, TimeProvider.System).RunAsync();
        Assert.False(File.Exists(lockedPath));
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly SqliteConnection connection = new("Data Source=:memory:");
        private readonly string root = Path.Combine(Path.GetTempPath(), "control-history-delete-" + Guid.NewGuid().ToString("N"));
        public ControlDbContext Db { get; private set; } = null!;
        public OperationResultStore Results { get; private set; } = null!;
        public ControlHistoryDeletionService Deletion { get; private set; } = null!;
        public InventoryStore Store { get; private set; } = null!;
        public Guid HostId { get; } = Guid.NewGuid();
        private Guid ServerId { get; } = Guid.NewGuid();
        public string ResultPath(Guid id) => Path.Combine(root, "operation-results", id.ToString("N") + ".json");

        public static async Task<Fixture> CreateAsync()
        {
            var fixture = new Fixture();
            Directory.CreateDirectory(fixture.root);
            await fixture.connection.OpenAsync();
            fixture.Db = new(new DbContextOptionsBuilder<ControlDbContext>().UseSqlite(fixture.connection).Options);
            await fixture.Db.Database.EnsureCreatedAsync();
            var settings = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> { ["Control:DataRoot"] = fixture.root }).Build();
            var store = new InventoryStore(fixture.Db, new InventoryWriteGate(), TimeProvider.System, settings);
            fixture.Store = store;
            fixture.Results = new(settings, fixture.Db);
            fixture.Deletion = new(fixture.Db, store, fixture.Results, TimeProvider.System);
            fixture.Db.Hosts.Add(new InventoryHost { HostId = fixture.HostId, HostKey = "fixture", DisplayName = "Fixture" });
            fixture.Db.Servers.Add(new InventoryServer { ServerId = fixture.ServerId, HostId = fixture.HostId, ServerKey = "fixture", DisplayName = "Fixture" });
            await fixture.Db.SaveChangesAsync();
            return fixture;
        }

        public async Task<(ControlWorkflow, ControlOperation, ControlReservationLease)> AddAsync()
        {
            var now = DateTimeOffset.UtcNow;
            var workflow = new ControlWorkflow { WorkflowId = Guid.NewGuid(), Status = WorkflowStatus.Succeeded, RequestJson = "request", CreatedAtUtc = now, UpdatedAtUtc = now };
            var lease = new ControlReservationLease { ReservationId = Guid.NewGuid(), WorkflowId = workflow.WorkflowId, Status = ReservationStatus.Released, CreatedAtUtc = now, ReleasedAtUtc = now };
            var operation = new ControlOperation { OperationId = Guid.NewGuid(), IdempotencyKey = Guid.NewGuid(), HostId = HostId, ServerId = ServerId, WorkflowId = workflow.WorkflowId, ReservationId = lease.ReservationId,
                Kind = OperationKind.ReadFile, Status = OperationStatus.Succeeded, CreatedAtUtc = now, FinishedAtUtc = now,
                CommandJson = "command", CommandSha256 = ControlProtocol.Hash("command"), RequestSha256 = ControlProtocol.Hash("request"), ResultJson = "result" };
            Db.Workflows.Add(workflow); Db.ReservationLeases.Add(lease); Db.Operations.Add(operation);
            await Db.SaveChangesAsync();
            return (workflow, operation, lease);
        }

        public async ValueTask DisposeAsync()
        {
            await Db.DisposeAsync(); await connection.DisposeAsync();
            Directory.Delete(root, true);
        }
    }
}
