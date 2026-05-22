namespace AstralRecordApi.Data.Entities;

public class MasterDataEntryEntity
{
    public Guid EntryId { get; set; }
    public Guid SourceId { get; set; }
    public string MasterType { get; set; } = string.Empty;
    public string MasterId { get; set; } = string.Empty;
    public string? Category { get; set; }
    public string? Type { get; set; }
    public int SchemaVersion { get; set; }
    public string? DisplayName { get; set; }
    public string SourceFilePath { get; set; } = string.Empty;
    public string SourceFileHash { get; set; } = string.Empty;
    public string PayloadJson { get; set; } = string.Empty;
    public long PayloadVersion { get; set; } = 1;
    public DateTime EffectiveFrom { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
