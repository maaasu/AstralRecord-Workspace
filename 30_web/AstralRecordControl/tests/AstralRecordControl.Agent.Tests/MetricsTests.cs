using AstralRecordControl.Agent;
using AstralRecordControl.Contracts;
using Xunit;

namespace AstralRecordControl.Agent.Tests;

public sealed class MetricsTests
{
    [Fact]
    public async Task SamplerComputesCpuFromCumulativeCounterAndLeavesGameValuesMissing()
    {
        var serverId = Guid.NewGuid(); var sessionId = Guid.NewGuid();
        var clock = new MutableClock(new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero));
        var runner = new FakeMetricsRunner(serverId, sessionId);
        var sampler = new AgentMetricsSampler(runner, new AgentConfiguration(), clock, new EmptyHostProbe());
        var snapshot = new RunnerSnapshot { Servers = [new ServerRuntimeSnapshot { ServerId = serverId, ProcessState = ProcessState.Running, CurrentSessionId = sessionId }] };
        var first = await sampler.SampleAsync(snapshot, true, CancellationToken.None);
        Assert.Null(Assert.Single(first.Servers).CpuPercent);
        runner.Ticks = TimeSpan.FromSeconds(2).Ticks;
        clock.Now = clock.Now.AddSeconds(10);
        var second = await sampler.SampleAsync(snapshot, true, CancellationToken.None);
        var process = Assert.Single(second.Servers);
        Assert.Equal(2d / 10d / Environment.ProcessorCount * 100d, process.CpuPercent!.Value, 5);
        Assert.Equal(1234L, process.MemoryUsedBytes);
        Assert.Null(process.PlayerCount); Assert.Null(process.Tps); Assert.Null(process.Mspt);
        var offline = await sampler.SampleAsync(snapshot, false, CancellationToken.None);
        Assert.Empty(offline.Servers);
        Assert.Null(offline.Host.CpuPercent);
    }

    [Fact]
    public async Task SamplerOnlyQueriesAppliedRunningJavaPortsAndPreservesZero()
    {
        var root = Path.Combine(Path.GetTempPath(), "control-metrics-" + Guid.NewGuid().ToString("N"));
        try
        {
            var hostId = Guid.NewGuid();
            using var store = new AgentStore(root, hostId);
            var configs = new[]
            {
                Config(hostId, ServerKind.Java, 25565), Config(hostId, ServerKind.Java, 25566),
                Config(hostId, ServerKind.Bedrock, 19132), Config(hostId, ServerKind.Java, null)
            };
            var snapshot = new InventorySnapshot { HostId = hostId, Revision = 1, Servers = configs };
            var json = ControlJson.Serialize(snapshot);
            var envelope = new InventoryEnvelope(ControlProtocol.Version, hostId, 1, json, ControlProtocol.Hash(json));
            store.PrepareInventory(envelope); store.CommitInventory(envelope);
            var runtime = new RunnerSnapshot { Servers =
            [
                new ServerRuntimeSnapshot { ServerId = configs[0].ServerId, ProcessState = ProcessState.Running },
                new ServerRuntimeSnapshot { ServerId = configs[1].ServerId, ProcessState = ProcessState.Stopped },
                new ServerRuntimeSnapshot { ServerId = configs[2].ServerId, ProcessState = ProcessState.Running },
                new ServerRuntimeSnapshot { ServerId = configs[3].ServerId, ProcessState = ProcessState.Running }
            ] };
            var gameProbe = new FakeGameProbe();
            var sampler = new AgentMetricsSampler(new FakeMetricsRunner(Guid.NewGuid(), Guid.NewGuid()),
                new AgentConfiguration(), hostProbe: new EmptyHostProbe(), store: store, gameProbe: gameProbe);
            var sample = await sampler.SampleAsync(runtime, true, CancellationToken.None);
            Assert.Equal([25565], gameProbe.Ports);
            Assert.Equal(0, sample.Servers.Single(s => s.ServerId == configs[0].ServerId).PlayerCount);
            Assert.All(sample.Servers.Skip(1), s => Assert.Null(s.PlayerCount));
        }
        finally { if (Directory.Exists(root)) Directory.Delete(root, recursive: true); }
    }

    private static ServerConfig Config(Guid hostId, ServerKind kind, int? port) => new()
    {
        ServerId = Guid.NewGuid(), HostId = hostId, Revision = 1, Kind = kind,
        ManagedRootId = Guid.NewGuid(), ExecutableId = Guid.NewGuid(),
        ServerDirectory = "server", ListenPort = port
    };
    private sealed class FakeGameProbe : IJavaServerStatusProbe
    {
        public List<int> Ports { get; } = [];
        public Task<int?> QueryPlayerCountAsync(int port, CancellationToken cancellationToken)
        { Ports.Add(port); return Task.FromResult<int?>(0); }
    }

    private sealed class MutableClock(DateTimeOffset now) : TimeProvider
    {
        public DateTimeOffset Now { get; set; } = now;
        public override DateTimeOffset GetUtcNow() => Now;
    }
    private sealed class EmptyHostProbe : IHostMetricsProbe
    {
        public HostMetricSample Sample() => new();
    }
    private sealed class FakeMetricsRunner(Guid serverId, Guid sessionId) : IRunnerClient
    {
        public long Ticks { get; set; }
        public Task<RunnerMetricsSnapshot> ReadMetricsAsync(CancellationToken cancellationToken) => Task.FromResult(new RunnerMetricsSnapshot
        {
            Processes = [new RunnerProcessMetric { ServerId = serverId, SessionId = sessionId, TotalProcessorTicks = Ticks, WorkingSetBytes = 1234, UptimeSeconds = 50 }]
        });
        public Task<RunnerSnapshot> GetSnapshotAsync(CancellationToken cancellationToken) => throw new NotImplementedException();
        public Task<RunnerSnapshot> ApplyInventoryAsync(InventoryEnvelope inventory, CancellationToken cancellationToken) => throw new NotImplementedException();
    }
}
