using AstralRecordControl.Contracts;
using AstralRecordControl.Runner;
using Xunit;

namespace AstralRecordControl.Runner.Tests;

public sealed class RunnerDataTests
{
    [Fact]
    public void InterruptedRestoreKeepsHoldAndBlocksStartUntilExplicitJournalResume()
    {
        using var fixture = new DataFixture();
        ExecuteRequest request;
        ResourceHold hold;
        using (var runner = fixture.Open())
        {
            fixture.Apply(runner);
            hold = runner.AcquireHold(new AcquireHoldRequest(Guid.NewGuid(), Guid.NewGuid(), ["server:" + fixture.Server.ServerId], HoldMode.Maintenance));
            request = fixture.Command(runner, hold, OperationKind.RestoreBackup, new RestoreBackupPayload(Guid.NewGuid(), RestoreMode.Replace));
            Assert.Equal(OperationStatus.Running, runner.BeginDataOperation(request).Status);
            Assert.Equal(OperationStatus.Running, runner.VerifyDataOperation(request.OperationId).Status);
            Assert.Equal(ErrorCode.RecoveryRequired, Assert.Throws<RunnerFault>(() => runner.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence))).Code);
        }
        using (var restarted = fixture.Open())
        {
            Assert.Equal(OperationStatus.NeedsAttention, restarted.GetDataOperation(request.OperationId).Status);
            Assert.True(restarted.InspectRecovery(new(fixture.Server.ServerId)).RecoveryRequired);
            var start = fixture.Command(restarted, hold, OperationKind.Start, new { });
            Assert.Equal(ErrorCode.RecoveryRequired, Assert.Throws<RunnerFault>(() => restarted.Execute(start)).Code);
            Assert.Equal(ErrorCode.RecoveryRequired, Assert.Throws<RunnerFault>(() => restarted.Recover(new RunnerRecoveryRequest(fixture.Server.ServerId, null, WorkflowRecoveryAction.AbortAfterInspection, "restore interrupted"), true)).Code);
            Assert.Equal(OperationStatus.Running, restarted.ResumeDataOperation(new AgentResumeDataOperationRequest(Guid.Empty, request.OperationId, "inspected restore journal")).Status);
            Assert.Equal(OperationStatus.Running, restarted.VerifyDataOperation(request.OperationId).Status);
            Assert.Equal(OperationStatus.Succeeded, restarted.FinishDataOperation(new(request.OperationId, request.CommandSha256, OperationStatus.Succeeded)).Status);
            restarted.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
            Assert.Empty(restarted.GetSnapshot().Holds);
        }
    }

    [Fact]
    public void DataBeginRejectsStaleFenceAndSameIdWithDifferentHash()
    {
        using var fixture = new DataFixture();
        using var runner = fixture.Open();
        fixture.Apply(runner);
        var hold = runner.AcquireHold(new AcquireHoldRequest(Guid.NewGuid(), Guid.NewGuid(), ["server:" + fixture.Server.ServerId], HoldMode.Exclusive));
        var request = fixture.Command(runner, hold, OperationKind.WriteFile, new WriteFilePayload(new FileTarget(fixture.Server.ManagedRootId, "server/server.properties"), "absent", "motd=test"));
        var command = ControlJson.Deserialize<OperationCommand>(request.CommandJson);
        var stale = command with { ReservationFence = hold.Fence + 1 };
        var staleJson = ControlJson.Serialize(stale);
        Assert.Equal(ErrorCode.ReservationConflict, Assert.Throws<RunnerFault>(() => runner.BeginDataOperation(new(request.OperationId, staleJson, ControlProtocol.Hash(staleJson)))).Code);
        Assert.Equal(OperationStatus.Running, runner.BeginDataOperation(request).Status);
        var changed = command with { Payload = ControlJson.Element(new WriteFilePayload(new FileTarget(fixture.Server.ManagedRootId, "server/server.properties"), "absent", "motd=changed")) };
        var changedJson = ControlJson.Serialize(changed);
        Assert.Equal(ErrorCode.OperationConflict, Assert.Throws<RunnerFault>(() => runner.BeginDataOperation(new(request.OperationId, changedJson, ControlProtocol.Hash(changedJson)))).Code);
    }

    [Fact]
    public void InterruptedBackupCanResumeBeforeRestartIntentWasRecorded()
    {
        using var fixture = new DataFixture();
        ExecuteRequest request;
        ResourceHold hold;
        using (var runner = fixture.Open())
        {
            fixture.Apply(runner);
            hold = runner.AcquireHold(new AcquireHoldRequest(Guid.NewGuid(), Guid.NewGuid(), ["server:" + fixture.Server.ServerId], HoldMode.Maintenance));
            request = fixture.Command(runner, hold, OperationKind.CreateBackup, new CreateBackupPayload(Guid.NewGuid(), Guid.NewGuid(), 1));
            runner.BeginDataOperation(request);
        }
        using var restarted = fixture.Open();
        Assert.Equal(OperationStatus.NeedsAttention, restarted.GetDataOperation(request.OperationId).Status);
        Assert.Equal(OperationStatus.Running, restarted.ResumeDataOperation(new AgentResumeDataOperationRequest(Guid.Empty, request.OperationId, "verified completed backup receipt")).Status);
        Assert.Equal(OperationStatus.Running, restarted.VerifyDataOperation(request.OperationId).Status);
        restarted.FinishDataOperation(new(request.OperationId, request.CommandSha256, OperationStatus.Succeeded));
        restarted.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
    }

    [Fact]
    public void VerifiedFileConflictCanRejectAndReleaseWithoutSuppressingStartup()
    {
        using var fixture = new DataFixture(); using var runner = fixture.Open(); fixture.Apply(runner);
        var hold = runner.AcquireHold(new(Guid.NewGuid(), Guid.NewGuid(), ["server:" + fixture.Server.ServerId], HoldMode.Exclusive));
        var request = fixture.Command(runner, hold, OperationKind.WriteFile, new WriteFilePayload(new(fixture.Server.ManagedRootId, "server/file.txt"), "absent", "new"));
        runner.BeginDataOperation(request);
        Assert.Equal(OperationStatus.Rejected, runner.FinishDataOperation(new(request.OperationId, request.CommandSha256, OperationStatus.Rejected)).Status);
        runner.ReleaseHold(new(hold.ReservationId, hold.OwnerWorkflowId, hold.Fence));
        Assert.False(runner.InspectRecovery(new(fixture.Server.ServerId)).RecoveryRequired);
    }

    private sealed class DataFixture : IDisposable
    {
        private readonly string root = Path.Combine(Path.GetTempPath(), "control-data-runner-" + Guid.NewGuid().ToString("N"));
        private readonly RunnerConfiguration configuration;
        public ServerConfig Server { get; }
        public DataFixture()
        {
            var managed = Path.Combine(root, "managed");
            Directory.CreateDirectory(Path.Combine(managed, "server"));
            var binary = Path.Combine(root, "fixture-java.exe");
            File.WriteAllText(binary, "fixture; never started");
            var rootId = Guid.NewGuid();
            var executableId = Guid.NewGuid();
            var hostId = Guid.NewGuid();
            configuration = new RunnerConfiguration { HostId = hostId, EnableProcessControl = true, EnableFileControl = true,
                LocalResources = new HostResources { Roots = [new ManagedRoot { Id = rootId, Path = managed }], Executables = [new ManagedExecutable { Id = executableId, Path = binary, Kind = ServerKind.Java }] } };
            Server = new ServerConfig { ServerId = Guid.NewGuid(), HostId = hostId, ManagedRootId = rootId, ServerDirectory = "server", ExecutableId = executableId, Revision = 1, Kind = ServerKind.Java };
        }
        public RunnerStore Open() => new(Path.Combine(root, "runner"), configuration);
        public void Apply(RunnerStore runner)
        {
            var inventory = new InventorySnapshot { HostId = configuration.HostId, Revision = 1, Servers = [Server] };
            var json = ControlJson.Serialize(inventory);
            runner.ApplyInventory(new(ControlProtocol.Version, configuration.HostId, 1, json, ControlProtocol.Hash(json)));
        }
        public ExecuteRequest Command(RunnerStore runner, ResourceHold hold, OperationKind kind, object payload)
        {
            var state = Assert.Single(runner.GetSnapshot().Servers);
            var command = new OperationCommand { OperationId = Guid.NewGuid(), HostId = configuration.HostId, ServerId = Server.ServerId,
                WorkflowId = hold.OwnerWorkflowId, ReservationId = hold.ReservationId, ReservationFence = hold.Fence,
                Kind = kind, ConfigRevision = Server.Revision, ExpectedStateRevision = state.StateRevision,
                ExpectedServerSessionId = state.CurrentSessionId, StartBeforeUtc = DateTimeOffset.UtcNow.AddMinutes(1),
                Payload = ControlJson.Element(payload) };
            var json = ControlJson.Serialize(command);
            return new(command.OperationId, json, ControlProtocol.Hash(json));
        }
        public void Dispose() => Directory.Delete(root, true);
    }
}
