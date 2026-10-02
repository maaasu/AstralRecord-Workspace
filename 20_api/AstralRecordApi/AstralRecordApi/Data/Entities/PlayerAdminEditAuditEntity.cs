namespace AstralRecordApi.Data.Entities;

/// <summary>ゲームDBの確定領収書からManagementDBへ投影する長期監査記録です。</summary>
public sealed class PlayerAdminEditAuditEntity
{
    public Guid OperationId { get; set; }
    public Guid EditSessionId { get; set; }
    public Guid AccountId { get; set; }
    public Guid TargetUserUuid { get; set; }
    public Guid ActorUserUuid { get; set; }
    public string Reason { get; set; } = string.Empty;
    public string Action { get; set; } = string.Empty;
    public string RequestHash { get; set; } = string.Empty;
    public string BeforeJson { get; set; } = string.Empty;
    public string AfterJson { get; set; } = string.Empty;
    public DateTime OccurredAtUtc { get; set; }
    public string ProjectionStatus { get; set; } = "PROJECTED";
}
