namespace AstralRecordApi.Services;

/// <summary>
/// マーケット拡張トークンの item ID と、種類ごとの有効な加算上限を一元管理します。
/// </summary>
public static class MarketListingExpansion
{
    public const string AlphaTokenItemId = "market_expansion_token_alpha";
    public const string BetaTokenItemId = "market_expansion_token_beta";
    public const string GammaTokenItemId = "market_expansion_token_gamma";
    public const string DeltaTokenItemId = "market_expansion_token_delta";

    public const string CurrencyInventoryType = "CURRENCY";
    public const string GameInventoryProfile = "GAME";

    private static readonly IReadOnlyDictionary<string, int> MaximumSlotsByTokenItemId =
        new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase)
        {
            [AlphaTokenItemId] = 6,
            [BetaTokenItemId] = 9,
            [GammaTokenItemId] = 9,
            [DeltaTokenItemId] = 9,
        };

    public static IReadOnlyList<string> TokenItemIds { get; } =
        MaximumSlotsByTokenItemId.Keys.ToArray();

    /// <summary>
    /// CURRENCY inventory に保持されたトークン数量から有効な拡張枠数を求めます。
    /// 上限を超えた数量や未知の item ID は、所持データとしては保持したまま枠数には反映しません。
    /// </summary>
    public static int ResolveSlotCount(IEnumerable<(string? ItemId, long Quantity)> entries)
    {
        var effectiveQuantityByItemId = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);
        foreach (var entry in entries ?? [])
        {
            if (entry.ItemId is null
                || !MaximumSlotsByTokenItemId.TryGetValue(entry.ItemId, out var maximum)
                || entry.Quantity <= 0)
                continue;

            effectiveQuantityByItemId.TryGetValue(entry.ItemId, out var current);
            var remaining = maximum - current;
            if (remaining <= 0)
                continue;
            effectiveQuantityByItemId[entry.ItemId] = current + Math.Min(entry.Quantity, remaining);
        }

        return effectiveQuantityByItemId.Values.Sum(quantity => (int)quantity);
    }

    /// <summary>
    /// Tier の基本枠へ、トークンによる拡張枠を加えます。
    /// </summary>
    public static int AddToBaseLimit(int baseListingSlots, int expansionSlots)
    {
        var safeBase = Math.Max(0, baseListingSlots);
        var safeExpansion = Math.Max(0, expansionSlots);
        return (int)Math.Min(int.MaxValue, (long)safeBase + safeExpansion);
    }
}
