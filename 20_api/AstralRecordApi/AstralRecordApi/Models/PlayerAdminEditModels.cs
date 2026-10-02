namespace AstralRecordApi.Models;

public sealed record PlayerAdminEditStartRequest(Guid EditSessionId, string Reason);
public sealed record PlayerAdminRuntimeRegistrationRequest(Guid ServerSessionId, string Role,
    string? ItemCatalogHash, string? ClassCatalogHash);
public sealed record PlayerAdminDrainAckRequest(string ServerId, Guid ServerSessionId, Guid AccountId,
    Guid UserUuid, bool Saved, bool Offline, Guid AckId);
public sealed record PlayerAdminEditOperationRequest(Guid OperationId, long ExpectedRevision,
    string ExpectedStateHash, string ExpectedCatalogVersion, int? Level, string? ClassId,
    IReadOnlyList<PlayerAdminInventoryChange>? InventoryChanges);
public sealed record PlayerAdminEditCancelRequest(Guid OperationId, long ExpectedRevision);
public sealed record PlayerAdminInventoryChange(string Action, string? ItemId, Guid? InventoryEntryId,
    long? Quantity, DateTime? ExpectedUpdatedAt);

public sealed record PlayerAdminEditSessionResponse(Guid EditSessionId, Guid AccountId, Guid UserUuid,
    Guid ActorUserUuid, string Reason, string Status, long Revision, DateTime CreatedAtUtc,
    DateTime UpdatedAtUtc, DateTime ExpiresAtUtc, DateTime? CompletedAtUtc,
    int ExpectedServerCount, int AcknowledgedServerCount);
public sealed record PlayerAdminRuntimeRegistrationResponse(string ServerId, Guid ServerSessionId, string Role);
public sealed record PlayerAdminDrainResponse(Guid EditSessionId, Guid AccountId, Guid UserUuid,
    string Status, long Revision, bool SafeToDisconnect);
public sealed record PlayerAdminAccountResponse(Guid AccountId, Guid UserUuid, int Level,
    long TotalExperience, int HighestLevel, string ClassId, int ClassLevel,
    long ClassExperience, int ProgressVersion);
public sealed record PlayerAdminInventoryEntryResponse(Guid InventoryEntryId, int? SlotIndex,
    string ItemCategory, string? ItemId, string? InstanceType, Guid? InstanceId,
    long Quantity, string? MetadataJson, DateTime UpdatedAt,
    string? ResolvedItemId = null, string? DisplayName = null);
public sealed record PlayerAdminInventoryResponse(Guid InventoryId, string InventoryType,
    string InventoryProfile, int? SlotCapacity, bool IsEnabled,
    IReadOnlyList<PlayerAdminInventoryEntryResponse> Entries);
public sealed record PlayerAdminItemResponse(string ItemId, string Name, string Category, int MaxStack);
public sealed record PlayerAdminClassResponse(string ClassId, string Name, int MaxLevel);
public sealed record PlayerAdminEditorResponse(PlayerAdminEditSessionResponse Session,
    PlayerAdminAccountResponse Account, IReadOnlyList<PlayerAdminInventoryResponse> Inventories,
    IReadOnlyList<PlayerAdminItemResponse> AvailableItems,
    IReadOnlyList<PlayerAdminClassResponse> AvailableClasses, string ExpectedStateHash,
    string CatalogVersion);
