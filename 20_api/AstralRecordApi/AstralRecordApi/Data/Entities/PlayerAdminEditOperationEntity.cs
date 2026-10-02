namespace AstralRecordApi.Data.Entities;

public sealed class PlayerAdminEditOperationEntity
{
    public Guid OperationId { get; set; }
    public Guid EditSessionId { get; set; }
    public string RequestHash { get; set; } = string.Empty;
    public string Action { get; set; } = string.Empty;
    public string ResponseJson { get; set; } = string.Empty;
    public string BeforeJson { get; set; } = string.Empty;
    public string AfterJson { get; set; } = string.Empty;
    public DateTime CreatedAtUtc { get; set; }
    public DateTime? AuditProjectedAtUtc { get; set; }
}
