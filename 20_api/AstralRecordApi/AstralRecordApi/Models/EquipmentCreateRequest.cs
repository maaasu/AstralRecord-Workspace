namespace AstralRecordApi.Models;

public class EquipmentCreateRequest
{
    public required string EquipmentId { get; set; }
    public Guid AccountId { get; set; }
    public string Source { get; set; } = string.Empty;
    public Guid CreatedBy { get; set; }
}
