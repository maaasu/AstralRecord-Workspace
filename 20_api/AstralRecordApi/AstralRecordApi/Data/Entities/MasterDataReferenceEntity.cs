namespace AstralRecordApi.Data.Entities;

public class MasterDataReferenceEntity
{
    public Guid ReferenceId { get; set; }
    public Guid FromEntryId { get; set; }
    public string FromMasterType { get; set; } = string.Empty;
    public string FromMasterId { get; set; } = string.Empty;
    public string ReferenceType { get; set; } = string.Empty;
    public string ReferenceIdValue { get; set; } = string.Empty;
    public string? ReferencePath { get; set; }
    public bool IsRequired { get; set; } = true;
    public int SortOrder { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
