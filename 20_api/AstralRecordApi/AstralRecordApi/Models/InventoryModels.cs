namespace AstralRecordApi.Models;

public class InventoryCreateRequest
{
    public Guid AccountId { get; set; }
    public required string InventoryType { get; set; }
    public required string InventoryProfile { get; set; }
    public int? SlotCapacity { get; set; }
    public bool? IsEnabled { get; set; }
    public string? MetadataJson { get; set; }
    public Guid CreatedBy { get; set; }
}

public class InventoryUpdateRequest
{
    public string? InventoryProfile { get; set; }
    public int? SlotCapacity { get; set; }
    public bool? IsEnabled { get; set; }
    public string? MetadataJson { get; set; }
    public Guid UpdatedBy { get; set; }
}

public class InventoryResponse
{
    public Guid InventoryId { get; init; }
    public Guid AccountId { get; init; }
    public string InventoryType { get; init; } = string.Empty;
    public string InventoryProfile { get; init; } = string.Empty;
    public int? SlotCapacity { get; init; }
    public bool IsEnabled { get; init; }
    public string? MetadataJson { get; init; }
    public DateTime CreatedAt { get; init; }
    public DateTime UpdatedAt { get; init; }
    public Guid CreatedBy { get; init; }
    public Guid UpdatedBy { get; init; }
    public bool IsDeleted { get; init; }
}

public class InventoryEntryCreateRequest
{
    public int? SlotIndex { get; set; }
    public required string ItemCategory { get; set; }
    public string? ItemId { get; set; }
    public string? InstanceType { get; set; }
    public Guid? InstanceId { get; set; }
    public long Quantity { get; set; } = 1;
    public string? MetadataJson { get; set; }
    public Guid CreatedBy { get; set; }
}

public class InventoryEntryUpdateRequest
{
    public int? SlotIndex { get; set; }
    public required string ItemCategory { get; set; }
    public string? ItemId { get; set; }
    public string? InstanceType { get; set; }
    public Guid? InstanceId { get; set; }
    public long Quantity { get; set; } = 1;
    public string? MetadataJson { get; set; }
    public Guid UpdatedBy { get; set; }
}

public class InventoryEntryResponse
{
    public Guid InventoryEntryId { get; init; }
    public Guid InventoryId { get; init; }
    public int? SlotIndex { get; init; }
    public string ItemCategory { get; init; } = string.Empty;
    public string? ItemId { get; init; }
    public string? InstanceType { get; init; }
    public Guid? InstanceId { get; init; }
    public long Quantity { get; init; }
    public string? MetadataJson { get; init; }
    public DateTime CreatedAt { get; init; }
    public DateTime UpdatedAt { get; init; }
    public Guid CreatedBy { get; init; }
    public Guid UpdatedBy { get; init; }
    public bool IsDeleted { get; init; }
}
