using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Data;

public sealed partial class ControlDbContext
{
    public DbSet<MetricBucket> MetricBuckets => Set<MetricBucket>();

    partial void ConfigureMetrics(ModelBuilder builder)
    {
        builder.Entity<MetricBucket>().HasKey(x => new { x.HostId, x.MetricKey, x.Granularity, x.BucketUtcTicks });
        builder.Entity<MetricBucket>().HasIndex(x => new { x.Granularity, x.BucketUtcTicks });
    }
}

public sealed class MetricBucket
{
    public Guid HostId { get; set; }
    public string MetricKey { get; set; } = "";
    public string Granularity { get; set; } = "";
    public long BucketUtcTicks { get; set; }
    public long LastObservedUtcTicks { get; set; }
    public int SampleCount { get; set; }
    public string? LastProcessState { get; set; }
    public string TotalsJson { get; set; } = "{}";
}

public static class MetricsSchema
{
    public const string Sql = """
        CREATE TABLE MetricBuckets (
            HostId TEXT NOT NULL, MetricKey TEXT NOT NULL, Granularity TEXT NOT NULL,
            BucketUtcTicks INTEGER NOT NULL, LastObservedUtcTicks INTEGER NOT NULL,
            SampleCount INTEGER NOT NULL, LastProcessState TEXT NULL, TotalsJson TEXT NOT NULL,
            PRIMARY KEY (HostId, MetricKey, Granularity, BucketUtcTicks));
        CREATE INDEX IX_MetricBuckets_Granularity_BucketUtcTicks ON MetricBuckets(Granularity, BucketUtcTicks);
        """;
}
