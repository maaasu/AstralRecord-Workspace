namespace AstralRecordApi.Models;

public class SkillBindPresetResponse
{
    public Guid? SkillBindPresetId { get; init; }
    public Guid AccountId { get; init; }
    public int PresetIndex { get; init; }
    public IReadOnlyList<string?> ActiveSkillSlots { get; init; } = [];
    public string? LeftClickSkillId { get; init; }
    public IReadOnlyList<string?> PassiveSkillSlots { get; init; } = [];
    public bool IsUnlocked { get; init; }
    public bool IsSaved { get; init; }
    public int Version { get; init; }
    public DateTime? CreatedAt { get; init; }
    public DateTime? UpdatedAt { get; init; }
    public Guid? CreatedBy { get; init; }
    public Guid? UpdatedBy { get; init; }
}

public class SkillBindPresetUpsertRequest
{
    public IReadOnlyList<string?> ActiveSkillSlots { get; init; } = [];
    public string? LeftClickSkillId { get; init; }
    public IReadOnlyList<string?> PassiveSkillSlots { get; init; } = [];
    public bool? IsUnlocked { get; init; }
    public Guid UpdatedBy { get; init; }
}
