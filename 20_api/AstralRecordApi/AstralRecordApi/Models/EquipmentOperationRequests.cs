namespace AstralRecordApi.Models;

public class EquipmentEnchantDeleteRequest
{
    public Guid EquipmentInstanceId { get; set; }
    public int SlotIndex { get; set; }
    public Guid UpdatedBy { get; set; }
}

public class EquipmentDurabilityUpdateRequest
{
    public Guid EquipmentInstanceId { get; set; }
    public int DurabilityValue { get; set; }
    public Guid UpdatedBy { get; set; }
}

public class EquipmentRuneAttachRequest
{
    public Guid EquipmentInstanceId { get; set; }
    public required string RuneItemId { get; set; }
    public int? SlotIndex { get; set; }
    public Guid UpdatedBy { get; set; }
}

public class EquipmentRuneDetachRequest
{
    public Guid EquipmentInstanceId { get; set; }
    public int SlotIndex { get; set; }
    public Guid UpdatedBy { get; set; }
}
