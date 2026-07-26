namespace AstralRecordApi.Data.Entities;

public class AccountSkillTreeUnlockedNodeEntity
{
    public Guid AccountSkillTreeUnlockedNodeId { get; set; }
    public Guid AccountSkillTreeStateId { get; set; }
    public string NodeId { get; set; } = string.Empty;
    public string? ConsumedClassId { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }

    public AccountSkillTreeStateEntity? State { get; set; }
}
