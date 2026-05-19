namespace AstralRecordApi.Data.Entities;

public class EquipmentInstanceEnchantEntity
{
    public Guid EnchantId { get; set; }
    public Guid EquipmentInstanceId { get; set; }
    public int SlotIndex { get; set; }
    public int PoolIndex { get; set; }
    public string Status { get; set; } = string.Empty;
    public string Type { get; set; } = string.Empty;
    public decimal Value { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
}
