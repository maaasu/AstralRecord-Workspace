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

/// <summary>
/// Plugin が検出したスキルツリー構造不整合を補修する要求です。
/// </summary>
public class AccountSkillTreeInvalidStateRepairRequest
{
    /// <summary>補償メールを配信するユーザー UUID。</summary>
    public Guid UserId { get; init; }

    /// <summary>同一構造に対する再試行を一意にする SHA-256 形式のキー。</summary>
    public required string RepairKey { get; init; }

    /// <summary>監査用の更新者 UUID。</summary>
    public Guid UpdatedBy { get; init; }
}

public class AccountSkillTreeUnlockedNodeModel
{
    public required string NodeId { get; init; }
    public string? ConsumedClassId { get; init; }
}
