using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Data;

public sealed partial class ControlDbContext
{
    partial void ConfigureNotifications(ModelBuilder builder);
    public DbSet<NotificationEvent> NotificationEvents => Set<NotificationEvent>();
    public DbSet<NotificationDestination> NotificationDestinations => Set<NotificationDestination>();
    public DbSet<NotificationDelivery> NotificationOutbox => Set<NotificationDelivery>();

    partial void ConfigureNotifications(ModelBuilder builder)
    {
        builder.Entity<NotificationEvent>(b =>
        {
            b.HasKey(x => x.EventId);
            b.HasIndex(x => new { x.SourceHostId, x.SourceEventSeq }).IsUnique();
            b.HasIndex(x => x.SourceKey).IsUnique();
            b.HasIndex(x => x.OccurredAtUtc);
            b.HasIndex(x => x.RecordedAtUnixMs);
        });
        builder.Entity<NotificationDestination>(b =>
        {
            b.HasKey(x => x.DestinationProfileId);
            b.Property(x => x.Revision).IsConcurrencyToken();
        });
        builder.Entity<NotificationDelivery>(b =>
        {
            b.ToTable("NotificationOutbox");
            b.HasKey(x => new { x.EventId, x.DestinationProfileId });
            b.HasIndex(x => x.NextAttemptAtUtc);
            b.HasIndex(x => x.NextAttemptUnixMs);
            b.HasIndex(x => x.TerminalAtUnixMs);
            b.HasOne<NotificationEvent>().WithMany().HasForeignKey(x => x.EventId).OnDelete(DeleteBehavior.Restrict);
            b.HasOne<NotificationDestination>().WithMany().HasForeignKey(x => x.DestinationProfileId).OnDelete(DeleteBehavior.Restrict);
        });
    }
}

public sealed class NotificationEvent
{
    public Guid EventId { get; set; }
    public Guid? SourceHostId { get; set; }
    public long? SourceEventSeq { get; set; }
    public string? SourceKey { get; set; }
    public Guid? ServerId { get; set; }
    public Guid? OperationId { get; set; }
    public Guid? ScheduleId { get; set; }
    public Guid? ScheduleRunId { get; set; }
    public string? ScheduleStepsJson { get; set; }
    public string EventType { get; set; } = "";
    public string? Outcome { get; set; }
    public DateTimeOffset OccurredAtUtc { get; set; }
    public DateTimeOffset RecordedAtUtc { get; set; }
    public long RecordedAtUnixMs { get; set; }
}

public sealed class NotificationDestination
{
    public Guid DestinationProfileId { get; set; }
    public string DisplayName { get; set; } = "";
    // The whole URL is protected because path and query can contain credentials.
    public string ProtectedUrl { get; set; } = "";
    public bool Enabled { get; set; }
    public long Revision { get; set; } = 1;
    public DateTimeOffset CreatedAtUtc { get; set; }
    public DateTimeOffset UpdatedAtUtc { get; set; }
}

public sealed class NotificationDelivery
{
    public Guid EventId { get; set; }
    public Guid DestinationProfileId { get; set; }
    public string PayloadJson { get; set; } = "";
    // Attempt includes the initial delivery. Four sends total: initial + 3 retries.
    public int Attempt { get; set; }
    public DateTimeOffset NextAttemptAtUtc { get; set; }
    public long NextAttemptUnixMs { get; set; }
    public DateTimeOffset? SentAtUtc { get; set; }
    public DateTimeOffset? DeadLetteredAtUtc { get; set; }
    public long? TerminalAtUnixMs { get; set; }
    public string? LastErrorCode { get; set; }
}

public static class NotificationSchema
{
    public const string Sql = """
        CREATE TABLE NotificationEvents (EventId TEXT NOT NULL PRIMARY KEY, SourceHostId TEXT NULL, SourceEventSeq INTEGER NULL, SourceKey TEXT NULL, ServerId TEXT NULL, OperationId TEXT NULL, ScheduleId TEXT NULL, ScheduleRunId TEXT NULL, ScheduleStepsJson TEXT NULL, EventType TEXT NOT NULL, Outcome TEXT NULL, OccurredAtUtc TEXT NOT NULL, RecordedAtUtc TEXT NOT NULL, RecordedAtUnixMs INTEGER NOT NULL);
        CREATE UNIQUE INDEX IX_NotificationEvents_SourceHostId_SourceEventSeq ON NotificationEvents(SourceHostId, SourceEventSeq);
        CREATE UNIQUE INDEX IX_NotificationEvents_SourceKey ON NotificationEvents(SourceKey);
        CREATE INDEX IX_NotificationEvents_OccurredAtUtc ON NotificationEvents(OccurredAtUtc);
        CREATE INDEX IX_NotificationEvents_RecordedAtUnixMs ON NotificationEvents(RecordedAtUnixMs);
        CREATE TABLE NotificationDestinations (DestinationProfileId TEXT NOT NULL PRIMARY KEY, DisplayName TEXT NOT NULL, ProtectedUrl TEXT NOT NULL, Enabled INTEGER NOT NULL, Revision INTEGER NOT NULL, CreatedAtUtc TEXT NOT NULL, UpdatedAtUtc TEXT NOT NULL);
        CREATE TABLE NotificationOutbox (EventId TEXT NOT NULL, DestinationProfileId TEXT NOT NULL, PayloadJson TEXT NOT NULL, Attempt INTEGER NOT NULL, NextAttemptAtUtc TEXT NOT NULL, NextAttemptUnixMs INTEGER NOT NULL, SentAtUtc TEXT NULL, DeadLetteredAtUtc TEXT NULL, TerminalAtUnixMs INTEGER NULL, LastErrorCode TEXT NULL, PRIMARY KEY (EventId, DestinationProfileId), FOREIGN KEY(EventId) REFERENCES NotificationEvents(EventId) ON DELETE RESTRICT, FOREIGN KEY(DestinationProfileId) REFERENCES NotificationDestinations(DestinationProfileId) ON DELETE RESTRICT);
        CREATE INDEX IX_NotificationOutbox_NextAttemptAtUtc ON NotificationOutbox(NextAttemptAtUtc);
        CREATE INDEX IX_NotificationOutbox_NextAttemptUnixMs ON NotificationOutbox(NextAttemptUnixMs);
        CREATE INDEX IX_NotificationOutbox_TerminalAtUnixMs ON NotificationOutbox(TerminalAtUnixMs);
        """;
}
