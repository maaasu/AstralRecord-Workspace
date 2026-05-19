namespace AstralRecordApi.Data.Entities;

public class EquipmentInstanceRuneEntity
{
    public Guid RuneId { get; set; }
    public Guid EquipmentInstanceId { get; set; }
    public Guid? RuneInstanceId { get; set; }
    public int SlotIndex { get; set; }
    public string ItemId { get; set; } = string.Empty;
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
}
