namespace AstralRecordApi.Models;

public class AccountSkillTreeStateResponse
{
    public Guid? AccountSkillTreeStateId { get; init; }
    public Guid AccountId { get; init; }
    public required IReadOnlyList<AccountSkillTreeUnlockedNodeModel> UnlockedNodes { get; init; }
    public bool IsSaved { get; init; }
    public int Version { get; init; }
    public DateTime? CreatedAt { get; init; }
    public DateTime? UpdatedAt { get; init; }
    public Guid? CreatedBy { get; init; }
    public Guid? UpdatedBy { get; init; }
}

public class AccountSkillTreeStateUpsertRequest
{
    public required IReadOnlyList<AccountSkillTreeUnlockedNodeModel> UnlockedNodes { get; init; }
    public Guid UpdatedBy { get; init; }
}

public class AccountSkillTreeUnlockedNodeModel
{
    public required string NodeId { get; init; }
    public string? ConsumedClassId { get; init; }
}
