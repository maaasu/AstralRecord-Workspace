namespace AstralRecordApi.Data.Entities;

public sealed class PlayerAdminEditSessionEntity
{
    public Guid EditSessionId { get; set; }
    public Guid UserUuid { get; set; }
    public Guid AccountId { get; set; }
    public Guid ActorUserUuid { get; set; }
    public string Reason { get; set; } = string.Empty;
    public string Status { get; set; } = "DRAINING";
    public long Revision { get; set; } = 1;
    public int ExpectedServerCount { get; set; }
    public string? ItemCatalogHash { get; set; }
    public string? ClassCatalogHash { get; set; }
    public DateTime CreatedAtUtc { get; set; }
    public DateTime UpdatedAtUtc { get; set; }
    public DateTime ExpiresAtUtc { get; set; }
    public DateTime? CompletedAtUtc { get; set; }
}
