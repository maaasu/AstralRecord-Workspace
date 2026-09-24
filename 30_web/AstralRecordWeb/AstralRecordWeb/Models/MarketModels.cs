using AstralRecordWeb.Models;

namespace AstralRecordWeb.Models;

public sealed class MarketListingResponse
{
    public Guid ListingId { get; init; }
    public Guid SellerAccountId { get; init; }
    public string SellerAccountName { get; init; } = string.Empty;
    public int? SellerAccountSlotIndex { get; init; }
    public string ItemCategory { get; init; } = string.Empty;
    public string ItemId { get; init; } = string.Empty;
    public string? InstanceType { get; init; }
    public Guid? InstanceId { get; init; }
    public MarketEquipmentInstanceResponse? EquipmentInstance { get; init; }
    public long Quantity { get; init; }
    public long RemainingQuantity { get; init; }
    public string CurrencyId { get; init; } = string.Empty;
    public long UnitPrice { get; init; }
    public long TotalPrice { get; init; }
    public long PriceFloor { get; init; }
    public long? ReferenceUnitPrice { get; init; }
    public decimal? PriceDeviationRate { get; init; }
    public string PriceConfidence { get; init; } = string.Empty;
    public string? ValuationSignature { get; init; }
    public string? ValuationSnapshotJson { get; init; }
    public string Status { get; init; } = string.Empty;
    public string? StatusReason { get; init; }
    public DateTime ListedAt { get; init; }
    public DateTime ExpiresAt { get; init; }
}

public sealed record MarketListingItem(MarketListingResponse Listing, ItemMasterResponse? Item)
{
    public string Category => Item?.Category ?? Listing.ItemCategory;
    public string? Rarity => Item?.Rarity;
    public string DisplayName => PlainText(string.IsNullOrWhiteSpace(Item?.Name) ? Listing.ItemId : Item.Name);
    public IReadOnlyList<string> DisplayLore => Item?.Lore.Select(PlainText).ToArray() ?? [];
    private static string PlainText(string value) => System.Text.RegularExpressions.Regex.Replace(value, "[&§][0-9A-FK-ORX]", string.Empty, System.Text.RegularExpressions.RegexOptions.IgnoreCase);
    public string? EquipmentSlot => Item?.Equipment?.Slot;
    public int? RequiredLevel => Item?.Equipment?.RequiredLevel;
    public MarketEquipmentInstanceResponse? EquipmentInstance => Listing.EquipmentInstance;
    public IReadOnlyDictionary<string, decimal> NumericAttributes { get; init; } = new Dictionary<string, decimal>();
}

public sealed class MarketTradeHistoryResponse
{
    public Guid TransactionId { get; init; }
    public Guid SellerAccountId { get; init; }
    public string SellerAccountName { get; init; } = string.Empty;
    public Guid BuyerAccountId { get; init; }
    public string BuyerAccountName { get; init; } = string.Empty;
    public string ItemCategory { get; init; } = string.Empty;
    public string ItemId { get; init; } = string.Empty;
    public string? InstanceType { get; init; }
    public long Quantity { get; init; }
    public string CurrencyId { get; init; } = string.Empty;
    public long UnitPrice { get; init; }
    public long TotalPrice { get; init; }
    public DateTime CompletedAt { get; init; }
}

public sealed class MarketTradeHistoryPageResponse
{
    public IReadOnlyList<MarketTradeHistoryResponse> Items { get; init; } = [];
    public bool HasNextPage { get; init; }
}

public sealed record MarketTradeHistoryItem(MarketTradeHistoryResponse Transaction, ItemMasterResponse? Item)
{
    public string Category => Item?.Category ?? Transaction.ItemCategory;
    public string DisplayName => MarketText.PlainText(string.IsNullOrWhiteSpace(Item?.Name) ? Transaction.ItemId : Item.Name);
    public string? Rarity => Item?.Rarity;
    public string? Icon => Item?.Icon;
    public string SellerDisplayName => string.IsNullOrWhiteSpace(Transaction.SellerAccountName)
        ? "名前不明のアカウント"
        : Transaction.SellerAccountName;
    public string BuyerDisplayName => string.IsNullOrWhiteSpace(Transaction.BuyerAccountName)
        ? "名前不明のアカウント"
        : Transaction.BuyerAccountName;
}

public sealed record MarketTradeHistoryPage(
    IReadOnlyList<MarketTradeHistoryItem> Items,
    bool HasNextPage);

internal static class MarketText
{
    public static string PlainText(string value) => System.Text.RegularExpressions.Regex.Replace(
        value,
        "[&§][0-9A-FK-ORX]",
        string.Empty,
        System.Text.RegularExpressions.RegexOptions.IgnoreCase);
}

public sealed class MarketEquipmentInstanceResponse
{
    public Guid EquipmentInstanceId { get; init; }
    public string ItemId { get; init; } = string.Empty;
    public int EnhanceLevel { get; init; }
    public int RuneMaxSlots { get; init; }
    public int TranscendenceRank { get; init; }
    public int? DurabilityMax { get; init; }
    public int? DurabilityValue { get; init; }
    public IReadOnlyList<MarketEquipmentStatRollResponse> StatRolls { get; init; } = [];
    public IReadOnlyList<MarketEquipmentEnchantResponse> Enchants { get; init; } = [];
    public IReadOnlyList<MarketEquipmentRuneResponse> Runes { get; init; } = [];
}

public sealed class MarketEquipmentStatRollResponse
{
    public string Status { get; init; } = string.Empty;
    public string Min { get; init; } = string.Empty;
    public string Max { get; init; } = string.Empty;
    public int SortOrder { get; init; }
}

public sealed class MarketEquipmentEnchantResponse
{
    public int SlotIndex { get; init; }
    public string EnchantMasterId { get; init; } = string.Empty;
    public string Status { get; init; } = string.Empty;
    public string Type { get; init; } = string.Empty;
    public decimal Value { get; init; }
}

public sealed class MarketEquipmentRuneResponse
{
    public int SlotIndex { get; init; }
    public string ItemId { get; init; } = string.Empty;
}

public sealed record MarketNumericFilterDefinition(
    string Id,
    string Key,
    string Label,
    decimal Minimum,
    decimal Maximum);
