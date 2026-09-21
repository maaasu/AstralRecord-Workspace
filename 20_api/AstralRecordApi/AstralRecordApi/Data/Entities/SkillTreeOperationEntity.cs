namespace AstralRecordApi.Data.Entities;

public sealed class SkillTreeOperationEntity
{
    public Guid OperationId { get; set; }
    public Guid AccountId { get; set; }
    public Guid ActorUserId { get; set; }
    public string RequestHash { get; set; } = string.Empty;
    public string TargetServerId { get; set; } = string.Empty;
    public string ExpectedDefinitionGenerationId { get; set; } = string.Empty;
    public int ExpectedPlayerStateVersion { get; set; }
    public string ExpectedEvaluationFingerprint { get; set; } = string.Empty;
    public string Action { get; set; } = string.Empty;
    public string NodeId { get; set; } = string.Empty;
    public string? SourceClassId { get; set; }
    public string Status { get; set; } = string.Empty;
    public string? Reason { get; set; }
    public Guid? ClaimedServerSessionId { get; set; }
    public Guid? ClaimedAccountSessionId { get; set; }
    public string? LeaseTokenHash { get; set; }
    public DateTime? LeaseExpiresAtUtc { get; set; }
    public DateTime CreatedAtUtc { get; set; }
    public DateTime ExpiresAtUtc { get; set; }
    public DateTime? CompletedAtUtc { get; set; }
}
