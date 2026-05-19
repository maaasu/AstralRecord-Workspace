namespace AstralRecordApi.Data.Entities;

public class RuneInstanceEntity
{
    public Guid RuneInstanceId { get; set; }
    public Guid AccountId { get; set; }
    public string ItemId { get; set; } = string.Empty;
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
