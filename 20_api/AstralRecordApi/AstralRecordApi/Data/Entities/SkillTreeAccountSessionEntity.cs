namespace AstralRecordApi.Data.Entities;

/// <summary>終了したsessionも保持し、古い取得要求による処理権限の復活を防ぎます。</summary>
public sealed class SkillTreeAccountSessionEntity
{
    public Guid AccountSessionId { get; set; }
    public Guid AccountId { get; set; }
    public string ServerId { get; set; } = "";
    public Guid ServerSessionId { get; set; }
    public string DefinitionGenerationId { get; set; } = "";
    public string LeaseTokenHash { get; set; } = "";
    public DateTime CreatedAtUtc { get; set; }
    public DateTime ExpiresAtUtc { get; set; }
    public bool Closed { get; set; }
    public long ViewSequence { get; set; }
}
