namespace AstralRecordApi.Data.Entities;

public sealed class PlayerAdminServerRuntimeEntity
{
    public string ServerId { get; set; } = string.Empty;
    public Guid ServerSessionId { get; set; }
    public string Role { get; set; } = string.Empty;
    public bool Enabled { get; set; } = true;
    public string? ItemCatalogHash { get; set; }
    public string? ClassCatalogHash { get; set; }
    public DateTime RegisteredAtUtc { get; set; }
    public DateTime LastSeenUtc { get; set; }
}
