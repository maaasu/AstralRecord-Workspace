namespace AstralRecordApi.Data.Entities;

public class MasterDataSourceEntity
{
    public Guid SourceId { get; set; }
    public string SourceKey { get; set; } = string.Empty;
    public string SourcePath { get; set; } = string.Empty;
    public string SourceKind { get; set; } = string.Empty;
    public int? SchemaVersion { get; set; }
    public bool IsEnabled { get; set; } = true;
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
