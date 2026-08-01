namespace AstralRecordApi.Data.Entities;

public class SkillBindPresetEntity
{
    public Guid SkillBindPresetId { get; set; }
    public Guid AccountId { get; set; }
    public int PresetIndex { get; set; }
    public string ActiveSkillSlotsJson { get; set; } = "[]";
    public string? LeftClickSkillId { get; set; }
    public string PassiveSkillSlotsJson { get; set; } = "[]";
    public bool IsUnlocked { get; set; }
    public int Version { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
