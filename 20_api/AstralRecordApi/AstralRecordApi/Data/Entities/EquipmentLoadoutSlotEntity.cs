namespace AstralRecordApi.Data.Entities;

public class EquipmentLoadoutSlotEntity
{
    public Guid EquipmentLoadoutSlotId { get; set; }
    public Guid EquipmentLoadoutId { get; set; }
    public string SlotType { get; set; } = string.Empty;
    public int SlotIndex { get; set; }
    public Guid EquipmentInstanceId { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
