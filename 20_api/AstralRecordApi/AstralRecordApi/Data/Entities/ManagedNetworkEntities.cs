namespace AstralRecordApi.Data.Entities;

public sealed class ManagedNetworkSettingsEntity
{
    public int Id { get; set; } = 1;
    public int Revision { get; set; }
    public string SettingsJson { get; set; } = "{}";
    public DateTime UpdatedAtUtc { get; set; }
    public Guid? UpdatedBy { get; set; }
}

public sealed class ManagedNetworkBanEntity
{
    public Guid UserUuid { get; set; }
    public int Revision { get; set; }
    public bool IsBanned { get; set; }
    public DateTime? ExpiresAtUtc { get; set; }
    public string? Reason { get; set; }
    public DateTime UpdatedAtUtc { get; set; }
    public Guid UpdatedBy { get; set; }
}

public sealed class NetworkManagementAuditEntity
{
    public Guid AuditId { get; set; }
    public string Operation { get; set; } = string.Empty;
    public Guid? ActorUuid { get; set; }
    public Guid? TargetUuid { get; set; }
    public string? BeforeJson { get; set; }
    public string AfterJson { get; set; } = "{}";
    public DateTime OccurredAtUtc { get; set; }
}
