namespace AstralRecordApi.Models;

public class AccountSkillTreeStateResponse
{
    public Guid? AccountSkillTreeStateId { get; init; }
    public Guid AccountId { get; init; }
    public int SkillPoints { get; init; }
    public required IReadOnlyList<string> UnlockedNodeIds { get; init; }
    public bool IsSaved { get; init; }
    public int Version { get; init; }
    public DateTime? CreatedAt { get; init; }
    public DateTime? UpdatedAt { get; init; }
    public Guid? CreatedBy { get; init; }
    public Guid? UpdatedBy { get; init; }
}

public class AccountSkillTreeStateUpsertRequest
{
    public int SkillPoints { get; init; }
    public required IReadOnlyList<string> UnlockedNodeIds { get; init; }
    public Guid UpdatedBy { get; init; }
}
