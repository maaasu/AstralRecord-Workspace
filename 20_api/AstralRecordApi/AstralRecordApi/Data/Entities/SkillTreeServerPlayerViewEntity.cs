namespace AstralRecordApi.Data.Entities;

/// <summary>Plugin が同じ実ロード世代で評価した、Web表示専用のプレイヤー状態です。</summary>
public sealed class SkillTreeServerPlayerViewEntity
{
    public string ServerId { get; set; } = string.Empty;
    public Guid AccountId { get; set; }
    public Guid ServerSessionId { get; set; }
    public Guid? AccountSessionId { get; set; }
    public string DefinitionGenerationId { get; set; } = string.Empty;
    public int PlayerStateVersion { get; set; }
    public string EvaluationFingerprint { get; set; } = string.Empty;
    public bool EditEligible { get; set; }
    public bool OfflineConfirmed { get; set; }
    public string ViewJson { get; set; } = string.Empty;
    public DateTime LastSeenUtc { get; set; }
}
