namespace AstralRecordApi.Data.Entities;

public class AccountLearnedSkillEntity
{
    public Guid LearnedSkillId { get; set; }
    public Guid AccountId { get; set; }
    public string SkillId { get; set; } = string.Empty;
    public int Level { get; set; } = 1;
    public int Version { get; set; } = 1;
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }

    public List<AccountLearnedSkillSigilEntity> Sigils { get; set; } = [];
}
