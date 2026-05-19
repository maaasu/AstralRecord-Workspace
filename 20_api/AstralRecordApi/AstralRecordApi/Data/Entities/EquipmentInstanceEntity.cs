namespace AstralRecordApi.Data.Entities;

public class EquipmentInstanceEntity
{
    public Guid EquipmentInstanceId { get; set; }
    public Guid AccountId { get; set; }
    public string ItemId { get; set; } = string.Empty;
    public int EnhanceLevel { get; set; }
    public int RuneMaxSlots { get; set; }
    public int TranscendenceRank { get; set; }
    public int? DurabilityMax { get; set; }
    public int? DurabilityValue { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
