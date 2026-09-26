namespace AstralRecordApi.Models;

public sealed record ChannelBoostEffectResponse(double Multiplier, DateTime ExpiresAt,
    string ActivatorAccountName, Guid OperationId);
public sealed record ChannelBoostResponse(string ChannelId, ChannelBoostEffectResponse? Exp,
    ChannelBoostEffectResponse? Drop);
public sealed record ChannelBoostSnapshotResponse(long EventCursor, IReadOnlyList<ChannelBoostResponse> Channels);
public sealed record ChannelBoostEventResponse(long EventCursor, string ChannelId, Guid OperationId,
    Guid AccountId, string AccountName, string VipTier, string BoostKind, double Multiplier, DateTime ExpiresAt);
public sealed record ChannelBoostEventsResponse(long EventCursor, IReadOnlyList<ChannelBoostEventResponse> Events);
public sealed record ChannelBoostActivateRequest(Guid OperationId, Guid AccountId, Guid InventoryEntryId,
    DateTime ExpectedUpdatedAt);
public sealed record ChannelBoostActivateResponse(Guid OperationId, string Status, string? Reason,
    ChannelBoostResponse? Boost, long? EventCursor,
    InventoryOperationSnapshotResponse? InventorySnapshot = null);
