using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Data;

public sealed partial class ControlDbContext
{
    public DbSet<ScheduleOccurrenceRow> ScheduleOccurrences => Set<ScheduleOccurrenceRow>();
    public DbSet<ScheduleManualRequest> ScheduleManualRequests => Set<ScheduleManualRequest>();
    partial void ConfigureSchedules(ModelBuilder builder)
    {
        builder.Entity<ScheduleOccurrenceRow>(e => { e.ToTable("ScheduleOccurrences"); e.HasKey(x => x.OperationId); e.HasIndex(x => new { x.HostId, x.ScheduleId }); });
        builder.Entity<ScheduleManualRequest>(e => { e.ToTable("ScheduleManualRequests"); e.HasKey(x => x.OperationId); });
    }
}

public sealed class ScheduleOccurrenceRow
{
    public Guid OperationId { get; set; }
    public Guid HostId { get; set; }
    public Guid ScheduleId { get; set; }
    public string ReportJson { get; set; } = "";
    public DateTimeOffset UpdatedAtUtc { get; set; }
    public long AckedAgentEventSeq { get; set; }
}
public sealed class ScheduleManualRequest
{
    public Guid OperationId { get; set; }
    public Guid HostId { get; set; }
    public Guid ScheduleId { get; set; }
    public long Revision { get; set; }
    public string DefinitionJson { get; set; } = "";
    public string? RejectionCode { get; set; }
    public DateTimeOffset CreatedAtUtc { get; set; }
}

public static class ScheduleSchema
{
    public const string Sql = """
        CREATE TABLE ScheduleOccurrences(OperationId TEXT NOT NULL PRIMARY KEY,HostId TEXT NOT NULL,ScheduleId TEXT NOT NULL,ReportJson TEXT NOT NULL,UpdatedAtUtc TEXT NOT NULL,AckedAgentEventSeq INTEGER NOT NULL);
        CREATE INDEX IX_ScheduleOccurrences_HostId_ScheduleId ON ScheduleOccurrences(HostId,ScheduleId);
        CREATE TABLE ScheduleManualRequests(OperationId TEXT NOT NULL PRIMARY KEY,HostId TEXT NOT NULL,ScheduleId TEXT NOT NULL,Revision INTEGER NOT NULL,DefinitionJson TEXT NOT NULL,RejectionCode TEXT NULL,CreatedAtUtc TEXT NOT NULL);
        """;
}
