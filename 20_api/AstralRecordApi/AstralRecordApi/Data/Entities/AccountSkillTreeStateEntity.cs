namespace AstralRecordApi.Data.Entities;

public class AccountSkillTreeStateEntity
{
    public Guid AccountSkillTreeStateId { get; set; }
    public Guid AccountId { get; set; }
    public int Version { get; set; } = 1;
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }

    public List<AccountSkillTreeUnlockedNodeEntity> UnlockedNodes { get; set; } = [];
}
