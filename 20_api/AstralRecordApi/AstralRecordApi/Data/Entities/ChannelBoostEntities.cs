namespace AstralRecordApi.Data.Entities;

public sealed class ChannelBoostEntity
{
    public string ChannelId { get; set; } = string.Empty;
    public double? ExpMultiplier { get; set; }
    public DateTime? ExpExpiresAt { get; set; }
    public Guid? ExpOperationId { get; set; }
    public string? ExpActivatorName { get; set; }
    public double? DropMultiplier { get; set; }
    public DateTime? DropExpiresAt { get; set; }
    public Guid? DropOperationId { get; set; }
    public string? DropActivatorName { get; set; }
}

public sealed class ChannelBoostOperationEntity
{
    public Guid OperationId { get; set; }
    public Guid AccountId { get; set; }
    public string ChannelId { get; set; } = string.Empty;
    public Guid? InventoryEntryId { get; set; }
    public string RequestHash { get; set; } = string.Empty;
    public string Status { get; set; } = string.Empty;
    public string? Reason { get; set; }
    public string? BoostKind { get; set; }
    public double? Multiplier { get; set; }
    public DateTime? ExpiresAt { get; set; }
    public long? EventCursor { get; set; }
    public DateTime CreatedAt { get; set; }
}

public sealed class ChannelBoostEventEntity
{
    public long EventCursor { get; set; }
    public Guid OperationId { get; set; }
    public Guid AccountId { get; set; }
    public string ChannelId { get; set; } = string.Empty;
    public string AccountName { get; set; } = string.Empty;
    public string VipTier { get; set; } = "NONE";
    public string BoostKind { get; set; } = string.Empty;
    public double Multiplier { get; set; }
    public DateTime ExpiresAt { get; set; }
    public DateTime CreatedAt { get; set; }
}

public sealed class ChannelBoostCursorEntity
{
    public int Id { get; set; } = 1;
    public long LastEventCursor { get; set; }
}
