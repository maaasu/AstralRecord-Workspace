namespace AstralRecordControl.Contracts;

// All measurements are nullable: an unavailable adapter, process, or counter is not zero.
public sealed record HostMetricSample
{
    public double? CpuPercent { get; init; }
    public long? MemoryUsedBytes { get; init; }
    public long? MemoryTotalBytes { get; init; }
    public long? DiskFreeBytes { get; init; }
    public long? DiskTotalBytes { get; init; }
    public long? UptimeSeconds { get; init; }
}

public sealed record ServerMetricSample
{
    public Guid ServerId { get; init; }
    public ProcessState ProcessState { get; init; }
    public double? CpuPercent { get; init; }
    public long? MemoryUsedBytes { get; init; }
    public long? UptimeSeconds { get; init; }
    public int? PlayerCount { get; init; }
    public double? Tps { get; init; }
    public double? Mspt { get; init; }
}

public sealed record AgentMetricsBatch
{
    public DateTimeOffset ObservedAtUtc { get; init; }
    public HostMetricSample Host { get; init; } = new();
    public ServerMetricSample[] Servers { get; init; } = [];
}

// Runner reports counters only for processes it currently owns. The Agent computes rates.
public sealed record RunnerProcessMetric
{
    public Guid ServerId { get; init; }
    public Guid SessionId { get; init; }
    public long? TotalProcessorTicks { get; init; }
    public long? WorkingSetBytes { get; init; }
    public long? UptimeSeconds { get; init; }
}

public sealed record RunnerMetricsSnapshot
{
    public RunnerProcessMetric[] Processes { get; init; } = [];
}
