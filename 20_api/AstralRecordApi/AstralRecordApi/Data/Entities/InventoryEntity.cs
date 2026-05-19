namespace AstralRecordApi.Data.Entities;

public class InventoryEntity
{
    public Guid InventoryId { get; set; }
    public Guid AccountId { get; set; }
    public string InventoryType { get; set; } = string.Empty;
    public string InventoryProfile { get; set; } = "GAME";
    public int? SlotCapacity { get; set; }
    public bool IsEnabled { get; set; }
    public string? MetadataJson { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
