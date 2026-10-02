namespace AstralRecordWeb.Models;

public sealed class PlayerAdminEditSession
{
    public Guid EditSessionId { get; init; }
    public Guid AccountId { get; init; }
    public Guid UserUuid { get; init; }
    public Guid ActorUserUuid { get; init; }
    public string Reason { get; init; } = string.Empty;
    public string Status { get; init; } = string.Empty;
    public long Revision { get; init; }
    public DateTimeOffset CreatedAtUtc { get; init; }
    public DateTimeOffset UpdatedAtUtc { get; init; }
    public DateTimeOffset? CompletedAtUtc { get; init; }
    public DateTimeOffset ExpiresAtUtc { get; init; }
    public int ExpectedServerCount { get; init; }
    public int AcknowledgedServerCount { get; init; }
}

public sealed class PlayerAdminEditor
{
    public PlayerAdminEditSession? Session { get; init; }
    public string ExpectedStateHash { get; init; } = string.Empty;
    public string CatalogVersion { get; init; } = string.Empty;
    public PlayerAdminAccountState? Account { get; init; }
    public IReadOnlyList<PlayerAdminInventory> Inventories { get; init; } = [];
    public IReadOnlyList<PlayerAdminAvailableItem> AvailableItems { get; init; } = [];
    public IReadOnlyList<PlayerAdminAvailableClass> AvailableClasses { get; init; } = [];
}

public sealed class PlayerAdminAccountState
{
    public Guid AccountId { get; init; }
    public Guid UserUuid { get; init; }
    public int Level { get; init; }
    public long TotalExperience { get; init; }
    public int HighestLevel { get; init; }
    public string ClassId { get; init; } = string.Empty;
    public int ClassLevel { get; init; }
    public long ClassExperience { get; init; }
    public long ProgressVersion { get; init; }
}

public sealed class PlayerAdminInventory
{
    public Guid InventoryId { get; init; }
    public string InventoryType { get; init; } = string.Empty;
    public string InventoryProfile { get; init; } = string.Empty;
    public int? SlotCapacity { get; init; }
    public bool IsEnabled { get; init; }
    public IReadOnlyList<PlayerAdminInventoryEntry> Entries { get; init; } = [];
}

public sealed class PlayerAdminInventoryEntry
{
    public Guid InventoryEntryId { get; init; }
    public int? SlotIndex { get; init; }
    public string ItemCategory { get; init; } = string.Empty;
    public string? ItemId { get; init; }
    public string? ResolvedItemId { get; init; }
    public string? DisplayName { get; init; }
    public string? InstanceType { get; init; }
    public Guid? InstanceId { get; init; }
    public long Quantity { get; init; }
    public string? MetadataJson { get; init; }
    public DateTime UpdatedAt { get; init; }
}

public sealed class PlayerAdminAvailableItem
{
    public string ItemId { get; init; } = string.Empty;
    public string Name { get; init; } = string.Empty;
    public string Category { get; init; } = string.Empty;
    public int MaxStack { get; init; }
}

public sealed class PlayerAdminAvailableClass
{
    public string ClassId { get; init; } = string.Empty;
    public string Name { get; init; } = string.Empty;
    public int MaxLevel { get; init; }
}

public sealed class PlayerAdminInventoryChange
{
    public string Action { get; init; } = string.Empty;
    public string? ItemId { get; init; }
    public Guid? InventoryEntryId { get; init; }
    public long? Quantity { get; init; }
    public DateTime? ExpectedUpdatedAt { get; init; }
}
