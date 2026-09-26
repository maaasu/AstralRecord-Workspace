namespace AstralRecordApi.Models;

public sealed record AstraldShopChannelResponse(string ChannelId, string DisplayName);
public sealed record AstraldShopItemResponse(string ItemId, string Name, int PricePaidAstrald,
    string EffectType, double EffectValue, int? DurationSeconds, bool RequiresChannel);
public sealed record AstraldShopCatalogResponse(IReadOnlyList<AstraldShopItemResponse> Items,
    IReadOnlyList<AstraldShopChannelResponse> Channels);
public sealed record AstraldShopPurchaseRequest(Guid OperationId, Guid AccountId, string ItemId,
    int ExpectedPricePaidAstrald, string? ChannelId);
public sealed record AstraldShopProcessRequest(Guid AccountId, bool PreparedOnline);
public sealed record AstraldShopPendingResponse(Guid OperationId, Guid AccountId);
public sealed record AstraldShopPurchaseResponse(Guid OperationId, string Status, string? Reason,
    Guid AccountId, string ItemId, string? ChannelId, long? PaidAstraldBalance = null,
    AccountBenefitsResponse? Benefits = null, ChannelBoostResponse? Boost = null,
    IReadOnlyList<Guid>? AffectedInventoryEntryIds = null,
    InventoryOperationSnapshotResponse? InventorySnapshot = null, long? EventCursor = null);
