using AstralRecordControl.Agent;
using AstralRecordControl.Agent.Storage;
using AstralRecordControl.Contracts;
using Xunit;

namespace AstralRecordControl.Agent.Tests;

public sealed class P2AgentOperationTests
{
    [Fact]
    public async Task WriteRunsAfterDurableAcceptanceAndDuplicateDispatchDoesNotRepeatIt()
    {
        using var fixture = new DataFixture();
        var runner = new DataRunner(fixture.Server);
        var handler = fixture.Handler(runner);
        var request = fixture.Dispatch(OperationKind.WriteFile, new WriteFilePayload(fixture.Target("note.txt"), "absent", "hello"));
        var first = await handler.DispatchAsync(request, fixture.Epoch, CancellationToken.None);
        Assert.Equal(OperationStatus.Accepted, first.Status);
        var result = await WaitForTerminalAsync(handler, request.OperationId, fixture.Epoch);
        Assert.Equal(OperationStatus.Succeeded, result.Status);
        Assert.Equal("hello", File.ReadAllText(Path.Combine(fixture.ManagedRoot, "server", "note.txt")));
        Assert.Equal(1, runner.BeginCount);
        Assert.True(runner.VerifyCount > 0);
        Assert.Equal(OperationStatus.Succeeded, (await handler.DispatchAsync(request, fixture.Epoch, CancellationToken.None)).Status);
        Assert.Equal(1, runner.BeginCount);
    }

    [Fact]
    public async Task LargeReadResultIsReplayedFromDurableSpoolWithoutRepeatingReadOperation()
    {
        using var fixture = new DataFixture();
        var file = Path.Combine(fixture.ManagedRoot, "server", "large.txt");
        File.WriteAllText(file, new string('A', 100_000));
        var runner = new DataRunner(fixture.Server);
        var handler = fixture.Handler(runner);
        var publications = 0;
        handler.SetDataSession(null, (_, _, _, _) =>
        {
            publications++;
            if (publications == 1) throw new IOException("temporary upload failure");
            return Task.CompletedTask;
        });
        var request = fixture.Dispatch(OperationKind.ReadFile, new ReadFilePayload(fixture.Target("large.txt")));
        await handler.DispatchAsync(request, fixture.Epoch, CancellationToken.None);
        await WaitUntilAsync(() => fixture.Store.GetPendingResult(request.OperationId) is not null);
        handler.SetDataSession(null, (_, _, json, _) =>
        {
            Assert.Contains(new string('A', 100), json);
            publications++;
            return Task.CompletedTask;
        });
        var result = await WaitForTerminalAsync(handler, request.OperationId, fixture.Epoch);
        Assert.Equal(OperationStatus.Succeeded, result.Status);
        Assert.True(result.Result.GetProperty("resultStored").GetBoolean());
        Assert.Null(fixture.Store.GetPendingResult(request.OperationId));
        Assert.Equal(1, runner.BeginCount);
        Assert.True(publications >= 2);
    }

    [Fact]
    public async Task EtagConflictRejectsWithoutKeepingAnUncertainDataOperation()
    {
        using var f = new DataFixture();
        File.WriteAllText(Path.Combine(f.ManagedRoot, "server", "conflict.txt"), "current");
        var handler = f.Handler(new DataRunner(f.Server));
        var request = f.Dispatch(OperationKind.WriteFile, new WriteFilePayload(f.Target("conflict.txt"), ControlProtocol.Hash("old"), "overwrite"));
        await handler.DispatchAsync(request, f.Epoch, CancellationToken.None);
        var result = await WaitForTerminalAsync(handler, request.OperationId, f.Epoch);
        Assert.Equal(OperationStatus.Rejected, result.Status); Assert.Equal("StateConflict", result.ErrorCode);
        Assert.Equal("current", File.ReadAllText(Path.Combine(f.ManagedRoot, "server", "conflict.txt")));
    }

    private static async Task<ExecutionSnapshot> WaitForTerminalAsync(AgentOperationHandler handler, Guid operationId, Guid epoch)
    {
        for (var attempt = 0; attempt < 100; attempt++)
        {
            var snapshot = await handler.QueryAsync(new AgentExecutionRequest(epoch, operationId), epoch, CancellationToken.None);
            if (OperationValidation.IsTerminal(snapshot.Status) || snapshot.Status == OperationStatus.NeedsAttention) return snapshot;
            await Task.Delay(20);
        }
        throw new TimeoutException("P2 operation did not settle.");
    }

    [Fact]
    public async Task RunningDirectoryExportRejectsBeforeStagingOrTransfer()
    {
        using var f = new DataFixture();
        var handler = f.Handler(new DataRunner(f.Server) { State = ProcessState.Running });
        var request = f.Dispatch(OperationKind.ExportFiles, new ExportFilesPayload(new(f.Server.ManagedRootId, "server"), TransferId: Guid.NewGuid()));
        await handler.DispatchAsync(request, f.Epoch, CancellationToken.None);
        var result = await WaitForTerminalAsync(handler, request.OperationId, f.Epoch);
        Assert.Equal(OperationStatus.Rejected, result.Status); Assert.Equal("StateConflict", result.ErrorCode);
        Assert.False(Directory.Exists(Path.Combine(Path.GetDirectoryName(f.ManagedRoot)!, "storage", "exports")));
    }

    [Fact]
    public async Task LostBeginReplyRetainsAttentionUntilRunnerLedgerIsReconciled()
    {
        using var f = new DataFixture();
        var runner = new DataRunner(f.Server) { LoseBeginReply = true }; var handler = f.Handler(runner);
        var request = f.Dispatch(OperationKind.WriteFile, new WriteFilePayload(f.Target("uncertain.txt"), "absent", "new"));
        await handler.DispatchAsync(request, f.Epoch, CancellationToken.None);
        var result = await WaitForTerminalAsync(handler, request.OperationId, f.Epoch);
        Assert.Equal(OperationStatus.NeedsAttention, result.Status);
        await handler.QueryAsync(new(f.Epoch, request.OperationId), f.Epoch, CancellationToken.None);
        Assert.Equal(OperationStatus.NeedsAttention, (await runner.GetDataOperationAsync(request.OperationId, CancellationToken.None)).Status);
        Assert.False(File.Exists(Path.Combine(f.ManagedRoot, "server", "uncertain.txt")));
    }

    [Fact]
    public void BackupPaginationIncludesProtectedAndFailedRecordsBeyondOneHundred()
    {
        using var f = new DataFixture();
        var directory = Path.Combine(Path.GetDirectoryName(f.ManagedRoot)!, "storage", "backups"); Directory.CreateDirectory(directory);
        for (var i = 0; i < 130; i++)
        {
            var id = Guid.NewGuid();
            var manifest = new BackupManifest { BackupId = id, ServerId = f.Server.ServerId, State = BackupState.Failed, Protected = true, CreatedAtUtc = DateTimeOffset.Parse("2026-10-18T00:00:00Z").AddMinutes(i) };
            File.WriteAllText(Path.Combine(directory, id.ToString("N") + ".json"), ControlJson.Serialize(new { manifest, destinationRootId = Guid.NewGuid() }));
        }
        var handler = f.Handler(new DataRunner(f.Server));
        var first = handler.ListBackups(new(f.Epoch, f.Server.ServerId), f.Epoch);
        Assert.Equal(100, first.Rows.Length); Assert.NotNull(first.NextCursor);
        var second = handler.ListBackups(new(f.Epoch, f.Server.ServerId, first.NextCursor), f.Epoch);
        Assert.Equal(30, second.Rows.Length); Assert.Null(second.NextCursor);
        Assert.Equal(130, first.Rows.Concat(second.Rows).Select(b => b.BackupId).Distinct().Count());
        Assert.Single(handler.ListBackups(new(f.Epoch, f.Server.ServerId, BackupId: second.Rows[20].BackupId), f.Epoch).Rows);
    }

    private static async Task WaitUntilAsync(Func<bool> predicate)
    {
        for (var attempt = 0; attempt < 100; attempt++)
        {
            if (predicate()) return;
            await Task.Delay(20);
        }
        throw new TimeoutException("Expected durable result spool was not written.");
    }

    private sealed class DataRunner(ServerConfig server) : IRunnerClient
    {
        private readonly System.Collections.Concurrent.ConcurrentDictionary<Guid, ExecutionSnapshot> states = new();
        public int BeginCount { get; private set; }
        public bool LoseBeginReply { get; init; }
        public ProcessState State { get; init; } = ProcessState.Stopped;
        public int VerifyCount { get; private set; }
        public Task<RunnerSnapshot> ApplyInventoryAsync(InventoryEnvelope inventory, CancellationToken cancellationToken) => throw new NotImplementedException();
        public Task<RunnerSnapshot> GetSnapshotAsync(CancellationToken cancellationToken) => Task.FromResult(new RunnerSnapshot
        {
            RunnerEpoch = Guid.NewGuid(), Servers = [new ServerRuntimeSnapshot { ServerId = server.ServerId, ConfigRevision = server.Revision, StateRevision = 1, ProcessState = State }]
        });
        public Task<ExecutionSnapshot> BeginDataOperationAsync(ExecuteRequest request, CancellationToken cancellationToken)
        {
            BeginCount++;
            var state = new ExecutionSnapshot { OperationId = request.OperationId, CommandSha256 = request.CommandSha256, Status = OperationStatus.Running, StatusVersion = 1, UpdatedAtUtc = DateTimeOffset.UtcNow };
            states[request.OperationId] = state;
            if (LoseBeginReply) throw new IOException("fixture lost committed Begin reply");
            return Task.FromResult(state);
        }
        public Task<ExecutionSnapshot> VerifyDataOperationAsync(Guid operationId, CancellationToken cancellationToken)
        {
            VerifyCount++;
            return Task.FromResult(states[operationId]);
        }
        public Task<ExecutionSnapshot> FinishDataOperationAsync(FinishDataOperationRequest request, CancellationToken cancellationToken)
        {
            var state = states[request.OperationId] with { Status = request.Status, StatusVersion = states[request.OperationId].StatusVersion + 1 };
            states[request.OperationId] = state;
            return Task.FromResult(state);
        }
        public Task<ExecutionSnapshot> GetDataOperationAsync(Guid operationId, CancellationToken cancellationToken) =>
            states.TryGetValue(operationId, out var state) ? Task.FromResult(state) : throw new AgentFault(ErrorCode.StateConflict);
    }

    private sealed class DataFixture : IDisposable
    {
        private readonly string root = Path.Combine(Path.GetTempPath(), "control-p2-agent-" + Guid.NewGuid().ToString("N"));
        private readonly Guid rootId = Guid.NewGuid();
        public readonly Guid Epoch = Guid.NewGuid();
        public string ManagedRoot => Path.Combine(root, "managed");
        public ServerConfig Server { get; }
        public AgentConfiguration Configuration { get; }
        public AgentStore Store { get; }
        public P2StorageService Storage { get; }
        public DataFixture()
        {
            Directory.CreateDirectory(Path.Combine(ManagedRoot, "server"));
            var exe = Path.Combine(root, "fixture.exe"); File.WriteAllText(exe, "fixture");
            var executableId = Guid.NewGuid();
            Configuration = new AgentConfiguration { HostId = Guid.NewGuid(), ServerUrl = "https://control.test/", EnableFileControl = true,
                LocalResources = new HostResources { Roots = [new ManagedRoot { Id = rootId, Path = ManagedRoot }], Executables = [new ManagedExecutable { Id = executableId, Path = exe, Kind = ServerKind.Java }] } };
            Server = new ServerConfig { ServerId = Guid.NewGuid(), HostId = Configuration.HostId, ManagedRootId = rootId, ServerDirectory = "server", ExecutableId = executableId, Revision = 1, Kind = ServerKind.Java };
            Store = new AgentStore(Path.Combine(root, "agent"), Configuration.HostId);
            var inventory = new InventorySnapshot { HostId = Configuration.HostId, Revision = 1, Servers = [Server] };
            var json = ControlJson.Serialize(inventory);
            var envelope = new InventoryEnvelope(ControlProtocol.Version, Configuration.HostId, 1, json, ControlProtocol.Hash(json));
            Store.PrepareInventory(envelope); Store.CommitInventory(envelope);
            Storage = new P2StorageService(Path.Combine(root, "storage"), Configuration.LocalResources);
            File.WriteAllText(Path.Combine(root, "marker"), "enabled");
        }
        public FileTarget Target(string name) => new(rootId, "server/" + name);
        public AgentOperationHandler Handler(IRunnerClient runner) => new(Configuration, Store, runner, new AgentProcessControlGate(Path.Combine(root, "marker")), Storage);
        public DispatchOperationRequest Dispatch(OperationKind kind, object payload)
        {
            var command = new OperationCommand { OperationId = Guid.NewGuid(), HostId = Configuration.HostId, ServerId = Server.ServerId,
                WorkflowId = Guid.NewGuid(), ReservationId = Guid.NewGuid(), ReservationFence = 1, Kind = kind, ConfigRevision = 1,
                ExpectedStateRevision = 1, StartBeforeUtc = DateTimeOffset.UtcNow.AddMinutes(1), Payload = ControlJson.Element(payload) };
            var json = ControlJson.Serialize(command);
            return new(ControlProtocol.Version, Epoch, command.OperationId, json, ControlProtocol.Hash(json));
        }
        public void Dispose() { Store.Dispose(); Directory.Delete(root, true); }
    }
}
