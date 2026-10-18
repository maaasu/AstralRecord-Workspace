using AstralRecordControl.Contracts;
using Xunit;

namespace AstralRecordControl.Agent.Tests;

public sealed class RuntimeMonitorTests
{
    [Fact]
    public void OfflineRuntimeChangesPersistAtomicallyAndRestartDoesNotDuplicate()
    {
        var root = Path.Combine(Path.GetTempPath(), "control-runtime-" + Guid.NewGuid().ToString("N"));
        var host = Guid.NewGuid(); var server = Guid.NewGuid(); var epoch = Guid.NewGuid(); var session = Guid.NewGuid();
        var now = new DateTimeOffset(2026, 10, 18, 0, 0, 0, TimeSpan.Zero);
        var config = new ServerConfig { ServerId = server, HostId = host, Revision = 1, ManagedRootId = Guid.NewGuid(), ExecutableId = Guid.NewGuid(), ServerDirectory = "dev" };
        var json = ControlJson.Serialize(new InventorySnapshot { HostId = host, Revision = 1, Servers = [config] });
        var envelope = new InventoryEnvelope(1, host, 1, json, ControlProtocol.Hash(json));
        var running = new ServerRuntimeSnapshot { ServerId = server, ConfigRevision = 1, StateRevision = 1, ProcessState = ProcessState.Running, CurrentSessionId = session };
        try
        {
            using (var store = new AgentStore(root, host))
            {
                store.PrepareInventory(envelope); store.CommitInventory(envelope);
                store.ObserveRuntime(new() { RunnerEpoch = epoch, Servers = [running] }, now);
                store.ObserveRuntime(new() { RunnerEpoch = epoch, Servers = [running with { StateRevision = 2 }] }, now.AddSeconds(10));
                Assert.Single(store.ReadEvents());
            }
            using var reopened = new AgentStore(root, host);
            reopened.ObserveRuntime(new() { RunnerEpoch = epoch, Servers = [running with { StateRevision = 3 }] }, now.AddSeconds(20));
            Assert.Single(reopened.ReadEvents());
            var stopped = running with { StateRevision = 4, ProcessState = ProcessState.Stopped, LastExitedAtUtc = now.AddSeconds(25), LastExitCode = 1, LastExitWasExpected = false };
            reopened.ObserveRuntime(new() { RunnerEpoch = epoch, Servers = [stopped] }, now.AddSeconds(30));
            var events = reopened.ReadEvents(); Assert.Equal(2, events.Length);
            var crash = ControlJson.Deserialize<RuntimeChangedBody>(events[1].Body.GetRawText());
            Assert.True(crash.UnexpectedExit); Assert.Equal(now.AddSeconds(30), events[1].OccurredAtUtc);
            reopened.Acknowledge(events[1].EventSeq); Assert.Empty(reopened.ReadEvents());
        }
        finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }

    [Fact]
    public void ExitMetadataChangesNotifyEvenWhenProcessAlreadyRestartedAndDoNotReplayOldCrash()
    {
        var root = Path.Combine(Path.GetTempPath(), "control-runtime-" + Guid.NewGuid().ToString("N"));
        var host = Guid.NewGuid(); var server = Guid.NewGuid(); var at = DateTimeOffset.UtcNow;
        try
        {
            using var store = new AgentStore(root, host);
            var config = new ServerConfig { HostId = host, ServerId = server, Revision = 1, ManagedRootId = Guid.NewGuid(), ExecutableId = Guid.NewGuid(), ServerDirectory = "dev" };
            var json = ControlJson.Serialize(new InventorySnapshot { HostId = host, Revision = 1, Servers = [config] });
            var envelope = new InventoryEnvelope(1, host, 1, json, ControlProtocol.Hash(json)); store.PrepareInventory(envelope); store.CommitInventory(envelope);
            var stopped = new ServerRuntimeSnapshot { ServerId = server, ConfigRevision = 1, ProcessState = ProcessState.Stopped };
            store.ObserveRuntime(new() { Servers = [stopped] }, at);
            var crash = stopped with { StateRevision = 2, LastExitedAtUtc = at.AddSeconds(1), LastExitWasExpected = false, LastExitCode = 1 };
            store.ObserveRuntime(new() { Servers = [crash] }, at.AddSeconds(2));
            Assert.True(ControlJson.Deserialize<RuntimeChangedBody>(Assert.Single(store.ReadEvents()).Body.GetRawText()).UnexpectedExit);
            var running = crash with { StateRevision = 3, ProcessState = ProcessState.Running, CurrentSessionId = Guid.NewGuid() };
            store.ObserveRuntime(new() { Servers = [running] }, at.AddSeconds(3));
            var restarted = running with { StateRevision = 5, CurrentSessionId = Guid.NewGuid(), LastExitedAtUtc = at.AddSeconds(4) };
            store.ObserveRuntime(new() { Servers = [restarted] }, at.AddSeconds(5));
            Assert.True(ControlJson.Deserialize<RuntimeChangedBody>(store.ReadEvents()[^1].Body.GetRawText()).UnexpectedExit);
            store.ObserveRuntime(new() { Servers = [restarted with { ProcessState = ProcessState.Stopped, StateRevision = 6 }] }, at.AddSeconds(6));
            Assert.False(ControlJson.Deserialize<RuntimeChangedBody>(store.ReadEvents()[^1].Body.GetRawText()).UnexpectedExit);
        }
        finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }

    [Fact]
    public void RuntimeFromUnregisteredServerDoesNotAdvanceOutbox()
    {
        var root = Path.Combine(Path.GetTempPath(), "control-runtime-" + Guid.NewGuid().ToString("N")); var host = Guid.NewGuid();
        try
        {
            using var store = new AgentStore(root, host);
            var json = ControlJson.Serialize(new InventorySnapshot { HostId = host, Revision = 1 });
            var envelope = new InventoryEnvelope(1, host, 1, json, ControlProtocol.Hash(json)); store.PrepareInventory(envelope); store.CommitInventory(envelope);
            Assert.Throws<AgentFault>(() => store.ObserveRuntime(new() { Servers = [new() { ServerId = Guid.NewGuid(), ProcessState = ProcessState.Running }] }, DateTimeOffset.UtcNow));
            Assert.Empty(store.ReadEvents()); Assert.Equal(0, store.GetState().MaxEventSequence);
        }
        finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }
}
