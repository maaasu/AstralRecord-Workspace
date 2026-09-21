namespace AstralRecordApi.Data.Entities;

public class AccountSkillTreeStateEntity
{
    public Guid AccountSkillTreeStateId { get; set; }
    public Guid AccountId { get; set; }
    public int Version { get; set; } = 1;
    /// <summary>
    /// この状態を最後に検証・確定したスキルツリー定義世代です。null は導入前の
    /// legacy 状態であり、Plugin による明示移行まで自動修復・Web 確定の対象にしません。
    /// </summary>
    public string? DefinitionGenerationId { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }

    public List<AccountSkillTreeUnlockedNodeEntity> UnlockedNodes { get; set; } = [];
}
