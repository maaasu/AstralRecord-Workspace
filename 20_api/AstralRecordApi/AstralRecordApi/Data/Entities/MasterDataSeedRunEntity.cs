namespace AstralRecordApi.Data.Entities;

public class MasterDataSeedRunEntity
{
    public Guid SeedRunId { get; set; }
    public string TriggerType { get; set; } = string.Empty;
    public string Status { get; set; } = string.Empty;
    public string SourceRootPath { get; set; } = string.Empty;
    public DateTime StartedAt { get; set; }
    public DateTime? FinishedAt { get; set; }
    public int FileCount { get; set; }
    public int UpsertedCount { get; set; }
    public int DeletedCount { get; set; }
    public int SkippedCount { get; set; }
    public string? ErrorMessage { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
}
