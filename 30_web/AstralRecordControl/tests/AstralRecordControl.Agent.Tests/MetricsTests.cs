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
