namespace AstralRecordApi.Models;

public class EquipmentEnchantRequest
{
    public Guid EquipmentInstanceId { get; set; }
    public int PoolIndex { get; set; }
    public Guid UpdatedBy { get; set; }
}

public class EquipmentEnchantDeleteRequest
{
    public Guid EquipmentInstanceId { get; set; }
    public int PoolIndex { get; set; }
}

public class EquipmentEnhanceRequest
{
    public Guid EquipmentInstanceId { get; set; }
    public int? TargetLevel { get; set; }
    public Guid UpdatedBy { get; set; }
}

public class EquipmentTranscendenceRequest
{
    public Guid EquipmentInstanceId { get; set; }
    public int? TargetRank { get; set; }
    public Guid UpdatedBy { get; set; }
}

public class EquipmentRuneAttachRequest
{
    public Guid EquipmentInstanceId { get; set; }
    public Guid? RuneInstanceId { get; set; }
    public string? RuneItemId { get; set; }
    public int? SlotIndex { get; set; }
    public Guid UpdatedBy { get; set; }
}

public class EquipmentRuneDetachRequest
{
    public Guid EquipmentInstanceId { get; set; }
    public int SlotIndex { get; set; }
}
