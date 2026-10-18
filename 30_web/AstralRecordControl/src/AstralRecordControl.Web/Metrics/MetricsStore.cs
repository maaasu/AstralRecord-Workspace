using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using Microsoft.EntityFrameworkCore;
using System.Text.Json;

namespace AstralRecordControl.Web.Metrics;

public sealed class MetricsStore(ControlDbContext db, TimeProvider clock)
{
    public const int MaximumReturnedBuckets = 2160;
    private const string HostKey = "host";
    private static readonly TimeSpan MaximumAge = TimeSpan.FromMinutes(2);

    // Called inside InventoryStore.WriteAsync, after the authenticated heartbeat and runtime were validated.
    public async Task AcceptAsync(Guid hostId, AgentMetricsBatch? batch, bool runnerAvailable, ServerRuntimeSnapshot[] snapshots)
    {
        if (batch is null) return;
        var now = clock.GetUtcNow();
        if (batch.ObservedAtUtc.Offset != TimeSpan.Zero || batch.ObservedAtUtc > now.AddSeconds(30) ||
            batch.ObservedAtUtc < now - MaximumAge || batch.Host is null || batch.Servers is null ||
            batch.Servers.Length > 32 || batch.Servers.Select(s => s.ServerId).Distinct().Count() != batch.Servers.Length)
            throw new InventoryFault(400, "InvalidInput");
        ValidateHost(batch.Host);
        if (!runnerAvailable && batch.Servers.Length != 0) throw new InventoryFault(400, "InvalidInput");
        var runtime = snapshots.ToDictionary(s => s.ServerId);
        var owned = (await db.Servers.AsNoTracking().Where(s => s.HostId == hostId).Select(s => s.ServerId).ToArrayAsync()).ToHashSet();
        foreach (var sample in batch.Servers)
        {
            if (sample.ServerId == Guid.Empty || !owned.Contains(sample.ServerId) ||
                !runtime.TryGetValue(sample.ServerId, out var state) || state.ProcessState != sample.ProcessState)
                throw new InventoryFault(400, "InvalidInput");
            ValidateServer(sample);
        }
        await AccumulateAsync(hostId, HostKey, batch.ObservedAtUtc, null, batch.Host, null);
        foreach (var sample in batch.Servers)
            await AccumulateAsync(hostId, sample.ServerId.ToString("D"), batch.ObservedAtUtc, sample.ProcessState, null, sample);
        await PruneAsync();
    }

    // Uses server time and can run without Agent heartbeats.
    public async Task PruneAsync()
    {
        var now = clock.GetUtcNow();
        await db.MetricBuckets.Where(row => row.Granularity == "minute" && row.BucketUtcTicks < now.AddDays(-7).UtcTicks ||
            row.Granularity == "hour" && row.BucketUtcTicks < now.AddDays(-90).UtcTicks).ExecuteDeleteAsync();
    }

    public async Task<ServerMetricSeries> QueryServerAsync(Guid serverId, DateTimeOffset fromUtc, DateTimeOffset toUtc, string granularity)
    {
        var now = clock.GetUtcNow();
        var bucketTicks = granularity switch { "minute" => TimeSpan.TicksPerMinute, "hour" => TimeSpan.TicksPerHour, _ => 0 };
        if (bucketTicks == 0 || fromUtc.Offset != TimeSpan.Zero || toUtc.Offset != TimeSpan.Zero ||
            toUtc <= fromUtc || toUtc > now.AddMinutes(1) || fromUtc < now.AddDays(-90) ||
            granularity == "minute" && fromUtc < now.AddDays(-7) ||
            (toUtc.UtcTicks - fromUtc.UtcTicks + bucketTicks - 1) / bucketTicks > MaximumReturnedBuckets)
            throw new InventoryFault(400, "InvalidInput");
        var server = await db.Servers.AsNoTracking().SingleOrDefaultAsync(s => s.ServerId == serverId)
            ?? throw new InventoryFault(404, "NotFound");
        var host = await db.Hosts.AsNoTracking().SingleAsync(h => h.HostId == server.HostId);
        var start = fromUtc.UtcTicks;
        var end = toUtc.UtcTicks;
        var rows = await db.MetricBuckets.AsNoTracking().Where(row => row.HostId == server.HostId &&
            (row.MetricKey == HostKey || row.MetricKey == serverId.ToString("D")) &&
            row.Granularity == granularity && row.BucketUtcTicks >= start && row.BucketUtcTicks < end)
            .OrderBy(row => row.BucketUtcTicks).ToArrayAsync();
        var runtime = host.ConnectionState == HostConnectionState.Online
            ? ControlJson.Deserialize<ServerRuntimeSnapshot[]>(host.RuntimeSnapshotJson).SingleOrDefault(s => s.ServerId == serverId)
            : null;
        return new ServerMetricSeries(serverId, server.HostId, host.ConnectionState.ToString(),
            runtime?.ProcessState.ToString(), granularity,
            rows.Where(row => row.MetricKey == HostKey).Select(ToPoint).ToArray(),
            rows.Where(row => row.MetricKey != HostKey).Select(ToPoint).ToArray());
    }

    private async Task AccumulateAsync(Guid hostId, string key, DateTimeOffset observedAt, ProcessState? processState,
        HostMetricSample? host, ServerMetricSample? server)
    {
        foreach (var (granularity, bucket) in new[]
        {
            ("minute", Floor(observedAt, TimeSpan.TicksPerMinute)),
            ("hour", Floor(observedAt, TimeSpan.TicksPerHour))
        })
        {
            var row = await db.MetricBuckets.FindAsync(hostId, key, granularity, bucket);
            if (row is null)
            {
                row = new MetricBucket { HostId = hostId, MetricKey = key, Granularity = granularity,
                    BucketUtcTicks = bucket, LastObservedUtcTicks = 0 };
                db.MetricBuckets.Add(row);
            }
            if (observedAt.UtcTicks <= row.LastObservedUtcTicks) continue;
            var totals = JsonSerializer.Deserialize<MetricTotals>(row.TotalsJson) ?? new MetricTotals();
            if (host is not null) totals.Add(host);
            if (server is not null) totals.Add(server);
            row.TotalsJson = JsonSerializer.Serialize(totals);
            row.LastObservedUtcTicks = observedAt.UtcTicks;
            row.SampleCount++;
            row.LastProcessState = processState?.ToString();
        }
    }

    private static long Floor(DateTimeOffset value, long interval) => value.UtcTicks / interval * interval;
    private static MetricPoint ToPoint(MetricBucket row)
    {
        var totals = JsonSerializer.Deserialize<MetricTotals>(row.TotalsJson) ?? new MetricTotals();
        return new MetricPoint(new DateTimeOffset(row.BucketUtcTicks, TimeSpan.Zero), row.SampleCount,
            row.LastProcessState, totals.Cpu.Mean, totals.MemoryUsed.Mean, totals.MemoryTotal.Mean,
            totals.DiskFree.Mean, totals.DiskTotal.Mean, totals.Uptime.Mean,
            totals.Players.Mean, totals.Tps.Mean, totals.Mspt.Mean);
    }

    private static void ValidateHost(HostMetricSample sample)
    {
        Percent(sample.CpuPercent);
        Nonnegative(sample.MemoryUsedBytes); Nonnegative(sample.MemoryTotalBytes);
        Nonnegative(sample.DiskFreeBytes); Nonnegative(sample.DiskTotalBytes); Nonnegative(sample.UptimeSeconds);
        if (sample.MemoryUsedBytes > sample.MemoryTotalBytes || sample.DiskFreeBytes > sample.DiskTotalBytes)
            throw new InventoryFault(400, "InvalidInput");
    }
    private static void ValidateServer(ServerMetricSample sample)
    {
        if (!Enum.IsDefined(sample.ProcessState)) throw new InventoryFault(400, "InvalidInput");
        Percent(sample.CpuPercent); Nonnegative(sample.MemoryUsedBytes); Nonnegative(sample.UptimeSeconds);
        if (sample.PlayerCount is < 0 or > 100000 || sample.Tps is < 0 or > 100 || sample.Mspt is < 0 or > 100000 ||
            sample.Tps is double tps && !double.IsFinite(tps) || sample.Mspt is double mspt && !double.IsFinite(mspt))
            throw new InventoryFault(400, "InvalidInput");
        if (sample.ProcessState != ProcessState.Running &&
            (sample.CpuPercent is not null || sample.MemoryUsedBytes is not null || sample.UptimeSeconds is not null ||
             sample.PlayerCount is not null || sample.Tps is not null || sample.Mspt is not null))
            throw new InventoryFault(400, "InvalidInput");
    }
    private static void Percent(double? value)
    {
        if (value is double x && (!double.IsFinite(x) || x < 0 || x > 100)) throw new InventoryFault(400, "InvalidInput");
    }
    private static void Nonnegative(long? value)
    {
        if (value is < 0) throw new InventoryFault(400, "InvalidInput");
    }
}

public sealed record ServerMetricSeries(Guid ServerId, Guid HostId, string ConnectionState, string? ProcessState,
    string Granularity, MetricPoint[] Host, MetricPoint[] Server);

public sealed record MetricPoint(DateTimeOffset AtUtc, int Samples, string? ProcessState, double? CpuPercent,
    double? MemoryUsedBytes, double? MemoryTotalBytes, double? DiskFreeBytes, double? DiskTotalBytes,
    double? UptimeSeconds, double? PlayerCount, double? Tps, double? Mspt);

public sealed class MetricTotals
{
    public MetricCounter Cpu { get; set; } = new();
    public MetricCounter MemoryUsed { get; set; } = new();
    public MetricCounter MemoryTotal { get; set; } = new();
    public MetricCounter DiskFree { get; set; } = new();
    public MetricCounter DiskTotal { get; set; } = new();
    public MetricCounter Uptime { get; set; } = new();
    public MetricCounter Players { get; set; } = new();
    public MetricCounter Tps { get; set; } = new();
    public MetricCounter Mspt { get; set; } = new();
    public void Add(HostMetricSample x)
    {
        Cpu.Add(x.CpuPercent); MemoryUsed.Add(x.MemoryUsedBytes); MemoryTotal.Add(x.MemoryTotalBytes);
        DiskFree.Add(x.DiskFreeBytes); DiskTotal.Add(x.DiskTotalBytes); Uptime.Add(x.UptimeSeconds);
    }
    public void Add(ServerMetricSample x)
    {
        Cpu.Add(x.CpuPercent); MemoryUsed.Add(x.MemoryUsedBytes); Uptime.Add(x.UptimeSeconds);
        Players.Add(x.PlayerCount); Tps.Add(x.Tps); Mspt.Add(x.Mspt);
    }
}

public sealed class MetricCounter
{
    public double Sum { get; set; }
    public int Count { get; set; }
    public double? Mean => Count == 0 ? null : Sum / Count;
    public void Add(double? value) { if (value is double v) { Sum += v; Count++; } }
    public void Add(long? value) { if (value is long v) { Sum += v; Count++; } }
    public void Add(int? value) { if (value is int v) { Sum += v; Count++; } }
}
