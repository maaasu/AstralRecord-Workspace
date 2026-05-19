namespace AstralRecordApi.Data.Entities;

public class EquipmentInstanceStatRollEntity
{
    public Guid StatRollId { get; set; }
    public Guid EquipmentInstanceId { get; set; }
    public string Status { get; set; } = string.Empty;
    public string RandomMin { get; set; } = string.Empty;
    public string RandomMax { get; set; } = string.Empty;
    public int SortOrder { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
}
