namespace AstralRecordApi.Data.Entities;

public class AccountLearnedSkillSigilEntity
{
    public Guid LearnedSkillSigilId { get; set; }
    public Guid LearnedSkillId { get; set; }
    public string SigilId { get; set; } = string.Empty;
    public string EquipGroupId { get; set; } = string.Empty;
    public int SlotIndex { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }

    public AccountLearnedSkillEntity? LearnedSkill { get; set; }
}
