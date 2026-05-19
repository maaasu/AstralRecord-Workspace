namespace AstralRecordApi.Data.Entities;

public class InventoryEntryEntity
{
    public Guid InventoryEntryId { get; set; }
    public Guid InventoryId { get; set; }
    public int? SlotIndex { get; set; }
    public string ItemCategory { get; set; } = string.Empty;
    public string? ItemId { get; set; }
    public string? InstanceType { get; set; }
    public Guid? InstanceId { get; set; }
    public long Quantity { get; set; }
    public string? MetadataJson { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
