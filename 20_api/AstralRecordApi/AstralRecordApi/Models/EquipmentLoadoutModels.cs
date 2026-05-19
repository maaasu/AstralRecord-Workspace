namespace AstralRecordApi.Models;

public class EquipmentLoadoutCreateRequest
{
    public Guid AccountId { get; set; }
    public required string LoadoutProfile { get; set; }
    public required string LoadoutName { get; set; }
    public int SortOrder { get; set; }
    public bool? IsActive { get; set; }
    public string? MetadataJson { get; set; }
    public Guid CreatedBy { get; set; }
}

public class EquipmentLoadoutUpdateRequest
{
    public string? LoadoutProfile { get; set; }
    public string? LoadoutName { get; set; }
    public int? SortOrder { get; set; }
    public bool? IsActive { get; set; }
    public string? MetadataJson { get; set; }
    public Guid UpdatedBy { get; set; }
}

public class EquipmentLoadoutActivateRequest
{
    public Guid UpdatedBy { get; set; }
}

public class EquipmentLoadoutSlotUpsertRequest
{
    public required string SlotType { get; set; }
    public int SlotIndex { get; set; }
    public Guid EquipmentInstanceId { get; set; }
    public Guid UpdatedBy { get; set; }
}

public class EquipmentLoadoutResponse
{
    public Guid EquipmentLoadoutId { get; init; }
    public Guid AccountId { get; init; }
    public string LoadoutProfile { get; init; } = string.Empty;
    public string LoadoutName { get; init; } = string.Empty;
    public int SortOrder { get; init; }
    public bool IsActive { get; init; }
    public string? MetadataJson { get; init; }
    public IReadOnlyList<EquipmentLoadoutSlotResponse> Slots { get; init; } = [];
    public DateTime CreatedAt { get; init; }
    public DateTime UpdatedAt { get; init; }
    public Guid CreatedBy { get; init; }
    public Guid UpdatedBy { get; init; }
    public bool IsDeleted { get; init; }
}

public class EquipmentLoadoutSlotResponse
{
    public Guid EquipmentLoadoutSlotId { get; init; }
    public Guid EquipmentLoadoutId { get; init; }
    public string SlotType { get; init; } = string.Empty;
    public int SlotIndex { get; init; }
    public Guid EquipmentInstanceId { get; init; }
    public DateTime CreatedAt { get; init; }
    public DateTime UpdatedAt { get; init; }
    public Guid CreatedBy { get; init; }
    public Guid UpdatedBy { get; init; }
    public bool IsDeleted { get; init; }
}
