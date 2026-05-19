namespace AstralRecordApi.Data.Entities;

public class EquipmentLoadoutEntity
{
    public Guid EquipmentLoadoutId { get; set; }
    public Guid AccountId { get; set; }
    public string LoadoutProfile { get; set; } = "GAME";
    public string LoadoutName { get; set; } = string.Empty;
    public int SortOrder { get; set; }
    public bool IsActive { get; set; }
    public string? MetadataJson { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
