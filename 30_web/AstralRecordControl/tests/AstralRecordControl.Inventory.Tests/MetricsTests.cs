using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Metrics;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordControl.Inventory.Tests;

public sealed class MetricsTests
{
    [Fact]
    public async Task MinuteAndHourAveragesPreserveMissingValuesAndExpireAtSevenAndNinetyDays()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:"); await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<ControlDbContext>().UseSqlite(connection).Options;
        await using var db = new ControlDbContext(options); await db.Database.EnsureCreatedAsync();
        var hostId = Guid.NewGuid(); var serverId = Guid.NewGuid();
        var start = new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero);
        var clock = new MutableClock(start);
        var runtime = new ServerRuntimeSnapshot { ServerId = serverId, ProcessState = ProcessState.Running };
        db.Hosts.Add(new InventoryHost { HostId = hostId, HostKey = "fixture", DisplayName = "fixture", ConnectionState = HostConnectionState.Online, RuntimeSnapshotJson = ControlJson.Serialize(new[] { runtime }) });
        db.Servers.Add(new InventoryServer { ServerId = serverId, HostId = hostId, ServerKey = "fixture-server", DisplayName = "fixture-server" });
        await db.SaveChangesAsync();
        var store = new MetricsStore(db, clock);
        await store.AcceptAsync(hostId, Batch(start, serverId, 20, null), true, [runtime]); await db.SaveChangesAsync();
        clock.Now = start.AddSeconds(10);
        await store.AcceptAsync(hostId, Batch(clock.Now, serverId, 40, 3), true, [runtime]); await db.SaveChangesAsync();
        var day = await store.QueryServerAsync(serverId, start, clock.Now.AddMinutes(1), "minute");
        var point = Assert.Single(day.Server);
        Assert.Equal(30, point.CpuPercent);
        Assert.Equal(3, point.PlayerCount);
        Assert.Null(point.Tps); Assert.Null(point.Mspt);
        Assert.Equal(2, point.Samples);
        Assert.Equal("Online", day.ConnectionState);
        db.Hosts.Local.Single().ConnectionState = HostConnectionState.Offline;
        await db.SaveChangesAsync();
        var offline = await store.QueryServerAsync(serverId, start, clock.Now.AddMinutes(1), "minute");
        Assert.Equal("Offline", offline.ConnectionState);
        Assert.Null(offline.ProcessState);
        clock.Now = start.AddDays(8);
        await store.PruneAsync(); // No heartbeat arrives while the Agent is offline.
        Assert.DoesNotContain(await db.MetricBuckets.ToArrayAsync(), x => x.Granularity == "minute" && x.BucketUtcTicks == start.UtcTicks);
        Assert.Contains(await db.MetricBuckets.ToArrayAsync(), x => x.Granularity == "hour" && x.BucketUtcTicks == start.UtcTicks);
        await Assert.ThrowsAsync<InventoryFault>(() => store.QueryServerAsync(serverId, start, clock.Now, "minute"));
        clock.Now = start.AddDays(91);
        await store.PruneAsync();
        Assert.DoesNotContain(await db.MetricBuckets.ToArrayAsync(), x => x.Granularity == "hour" && x.BucketUtcTicks == start.UtcTicks);
    }

    [Fact]
    public async Task RejectsForeignServerAndFutureSamplesWithoutWriting()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:"); await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<ControlDbContext>().UseSqlite(connection).Options;
        await using var db = new ControlDbContext(options); await db.Database.EnsureCreatedAsync();
        var hostId = Guid.NewGuid(); var serverId = Guid.NewGuid();
        var now = new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero);
        var clock = new MutableClock(now); var store = new MetricsStore(db, clock);
        var runtime = new ServerRuntimeSnapshot { ServerId = serverId, ProcessState = ProcessState.Running };
        await Assert.ThrowsAsync<InventoryFault>(() => store.AcceptAsync(hostId, Batch(now, serverId, 10, null), true, [runtime]));
        await Assert.ThrowsAsync<InventoryFault>(() => store.AcceptAsync(hostId,
            new AgentMetricsBatch { ObservedAtUtc = now.AddMinutes(1) }, true, []));
        Assert.Empty(db.ChangeTracker.Entries<MetricBucket>());
    }

    private static AgentMetricsBatch Batch(DateTimeOffset at, Guid serverId, double? cpu, int? players) => new()
    {
        ObservedAtUtc = at,
        Host = new HostMetricSample { CpuPercent = cpu, MemoryTotalBytes = 100, MemoryUsedBytes = 40 },
        Servers = [new ServerMetricSample { ServerId = serverId, ProcessState = ProcessState.Running,
            CpuPercent = cpu, MemoryUsedBytes = 30, PlayerCount = players }]
    };
    private sealed class MutableClock(DateTimeOffset now) : TimeProvider
    {
        public DateTimeOffset Now { get; set; } = now;
        public override DateTimeOffset GetUtcNow() => Now;
    }
}
