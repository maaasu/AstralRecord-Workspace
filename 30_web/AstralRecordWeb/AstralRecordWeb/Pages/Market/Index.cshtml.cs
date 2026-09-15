using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Market;

[Authorize]
public sealed class IndexModel(MarketApiClient marketApiClient) : PageModel
{
    public IReadOnlyList<MarketListingItem> Listings { get; private set; } = [];
    public IReadOnlyList<string> Categories { get; private set; } = [];
    public IReadOnlyList<string> Rarities { get; private set; } = [];
    public IReadOnlyList<string> EquipmentSlots { get; private set; } = [];
    public IReadOnlyList<MarketNumericFilterDefinition> NumericFilters { get; private set; } = [];
    public string? ErrorMessage { get; private set; }
    public int TotalListingCount { get; private set; }

    [BindProperty(SupportsGet = true)] public string? Query { get; set; }
    [BindProperty(SupportsGet = true)] public string? Category { get; set; }
    [BindProperty(SupportsGet = true)] public string? Rarity { get; set; }
    [BindProperty(SupportsGet = true)] public string? EquipmentSlot { get; set; }
    [BindProperty(SupportsGet = true)] public int? RequiredLevelMin { get; set; }
    [BindProperty(SupportsGet = true)] public int? RequiredLevelMax { get; set; }
    [BindProperty(SupportsGet = true)] public long? MinimumPrice { get; set; }
    [BindProperty(SupportsGet = true)] public long? MaximumPrice { get; set; }
    [BindProperty(SupportsGet = true)] public string? Sort { get; set; }

    public async Task OnGetAsync(CancellationToken cancellationToken)
    {
        try
        {
            var apiSort = Sort is "price_asc" or "price_desc" ? Sort : null;
            var source = await marketApiClient.GetActiveListingsAsync(
                Category, MinimumPrice, MaximumPrice, apiSort, cancellationToken);
            var items = await marketApiClient.GetItemsAsync(source.Select(listing => listing.ItemId), cancellationToken);
            var allListings = source.Select(listing => new MarketListingItem(
                listing,
                items.GetValueOrDefault(listing.ItemId))
            {
                NumericAttributes = ExtractNumericAttributes(listing, items.GetValueOrDefault(listing.ItemId)),
            }).ToArray();

            TotalListingCount = allListings.Length;
            Categories = allListings.Select(listing => listing.Category)
                .Where(value => !string.IsNullOrWhiteSpace(value))
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .OrderBy(value => value, StringComparer.OrdinalIgnoreCase).ToArray();
            Rarities = allListings.Select(listing => listing.Rarity)
                .Where(value => !string.IsNullOrWhiteSpace(value))
                .Select(value => value!).Distinct(StringComparer.OrdinalIgnoreCase)
                .OrderBy(value => value, StringComparer.OrdinalIgnoreCase).ToArray();
            EquipmentSlots = allListings.Select(listing => listing.EquipmentSlot)
                .Where(value => !string.IsNullOrWhiteSpace(value))
                .Select(value => value!).Distinct(StringComparer.OrdinalIgnoreCase)
                .OrderBy(value => value, StringComparer.OrdinalIgnoreCase).ToArray();
            NumericFilters = BuildNumericFilters(allListings);

            Listings = ApplyFilters(allListings, ReadNumericFilters())
                .OrderBy(listing => Sort is "newest" or null ? 0 : 1)
                .ThenBy(listing => Sort == "price_asc" ? listing.Listing.UnitPrice : long.MinValue)
                .ThenByDescending(listing => Sort == "price_desc" ? listing.Listing.UnitPrice : long.MinValue)
                .ThenByDescending(listing => listing.Listing.ListedAt)
                .ToArray();
        }
        catch (HttpRequestException exception)
        {
            ErrorMessage = exception.StatusCode is System.Net.HttpStatusCode.Unauthorized or System.Net.HttpStatusCode.Forbidden
                ? "マーケット情報 API の認証に失敗しました。Web 側の API キー設定を確認してください。"
                : "マーケット情報を取得できませんでした。しばらくしてから再試行してください。";
        }
        catch (JsonException)
        {
            ErrorMessage = "マーケット情報の形式が不正です。API と Web の更新状態を確認してください。";
        }
    }

    public string NumericMinimumName(MarketNumericFilterDefinition filter) => $"stat_{filter.Id}_min";
    public string NumericMaximumName(MarketNumericFilterDefinition filter) => $"stat_{filter.Id}_max";
    public string SellerName(MarketListingResponse listing) => listing.SellerAccountSlotIndex is null
        ? listing.SellerAccountName : $"{listing.SellerAccountName}#{listing.SellerAccountSlotIndex}";
    public string ItemStatText(ItemEquipmentStatResponse stat) => stat.Value is null
        ? string.Empty
        : string.Equals(stat.Value.Min, stat.Value.Max, StringComparison.Ordinal) ? stat.Value.Min : $"{stat.Value.Min} - {stat.Value.Max}";

    private IEnumerable<MarketListingItem> ApplyFilters(
        IEnumerable<MarketListingItem> listings,
        IReadOnlyDictionary<string, (decimal? Minimum, decimal? Maximum)> numericFilters)
    {
        var filtered = listings;
        if (!string.IsNullOrWhiteSpace(Query))
        {
            var query = Query.Trim();
            filtered = filtered.Where(listing => Contains(listing.DisplayName, query)
                || Contains(listing.Listing.ItemId, query)
                || listing.Item?.Lore.Any(line => Contains(line, query)) == true);
        }
        if (!string.IsNullOrWhiteSpace(Category))
            filtered = filtered.Where(listing => EqualsIgnoreCase(listing.Category, Category));
        if (!string.IsNullOrWhiteSpace(Rarity))
            filtered = filtered.Where(listing => EqualsIgnoreCase(listing.Rarity, Rarity));
        if (!string.IsNullOrWhiteSpace(EquipmentSlot))
            filtered = filtered.Where(listing => EqualsIgnoreCase(listing.EquipmentSlot, EquipmentSlot));
        if (RequiredLevelMin.HasValue)
            filtered = filtered.Where(listing => listing.RequiredLevel >= RequiredLevelMin.Value);
        if (RequiredLevelMax.HasValue)
            filtered = filtered.Where(listing => listing.RequiredLevel <= RequiredLevelMax.Value);

        return filtered.Where(listing => numericFilters.All(filter =>
            listing.NumericAttributes.TryGetValue(filter.Key, out var value)
            && (!filter.Value.Minimum.HasValue || value >= filter.Value.Minimum.Value)
            && (!filter.Value.Maximum.HasValue || value <= filter.Value.Maximum.Value)));
    }

    private IReadOnlyDictionary<string, (decimal? Minimum, decimal? Maximum)> ReadNumericFilters()
    {
        var selected = new Dictionary<string, (decimal? Minimum, decimal? Maximum)>();
        foreach (var filter in NumericFilters)
        {
            var minimum = ReadDecimal(NumericMinimumName(filter));
            var maximum = ReadDecimal(NumericMaximumName(filter));
            if (minimum.HasValue || maximum.HasValue)
                selected[filter.Key] = (minimum, maximum);
        }
        return selected;
    }

    private decimal? ReadDecimal(string key)
    {
        var value = Request.Query[key].FirstOrDefault();
        return decimal.TryParse(value, NumberStyles.Number, CultureInfo.InvariantCulture, out var parsed)
            ? parsed : null;
    }

    private static IReadOnlyList<MarketNumericFilterDefinition> BuildNumericFilters(IEnumerable<MarketListingItem> listings)
    {
        var values = listings.SelectMany(listing => listing.NumericAttributes)
            .GroupBy(attribute => attribute.Key, StringComparer.OrdinalIgnoreCase)
            .Select(group => new
            {
                Key = group.Key,
                Label = NumericLabel(group.Key),
                Minimum = group.Min(attribute => attribute.Value),
                Maximum = group.Max(attribute => attribute.Value),
            })
            .OrderBy(value => value.Label, StringComparer.OrdinalIgnoreCase);

        return values.Select(value => new MarketNumericFilterDefinition(
            Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value.Key))).ToLowerInvariant()[..16],
            value.Key, value.Label, value.Minimum, value.Maximum)).ToArray();
    }

    private static IReadOnlyDictionary<string, decimal> ExtractNumericAttributes(
        MarketListingResponse listing, ItemMasterResponse? item)
    {
        var values = new Dictionary<string, decimal>(StringComparer.OrdinalIgnoreCase)
        {
            ["listing.unit_price"] = listing.UnitPrice,
            ["listing.total_price"] = listing.TotalPrice,
            ["listing.quantity"] = listing.Quantity,
            ["listing.remaining_quantity"] = listing.RemainingQuantity,
            ["listing.price_floor"] = listing.PriceFloor,
        };
        Add(values, "listing.reference_unit_price", listing.ReferenceUnitPrice);
        Add(values, "listing.price_deviation_percent", listing.PriceDeviationRate * 100m);
        Add(values, "item.sale_value", item?.SaleValue);
        Add(values, "item.max_stack", item?.MaxStack);
        Add(values, "equipment.required_level", item?.Equipment?.RequiredLevel);

        if (item?.Equipment is not null)
        {
            foreach (var stat in item.Equipment.Stats)
            {
                var baseKey = $"equipment.stat.{stat.Status ?? "unknown"}.{stat.Type ?? "value"}";
                Add(values, baseKey + ".min", ParseDecimal(stat.Value?.Min));
                Add(values, baseKey + ".max", ParseDecimal(stat.Value?.Max));
            }
        }

        AddJsonNumbers(values, "listing.valuation", listing.ValuationSnapshotJson);
        return values;
    }

    private static void AddJsonNumbers(IDictionary<string, decimal> values, string prefix, string? json)
    {
        if (string.IsNullOrWhiteSpace(json)) return;
        try
        {
            using var document = JsonDocument.Parse(json);
            ReadJsonNumbers(values, prefix, document.RootElement);
        }
        catch (JsonException)
        {
            // Invalid optional valuation data is displayed as unavailable; it must not prevent market browsing.
        }
    }

    private static void ReadJsonNumbers(IDictionary<string, decimal> values, string path, JsonElement value)
    {
        switch (value.ValueKind)
        {
            case JsonValueKind.Object:
                foreach (var property in value.EnumerateObject())
                    ReadJsonNumbers(values, $"{path}.{property.Name}", property.Value);
                break;
            case JsonValueKind.Array:
                var index = 0;
                foreach (var item in value.EnumerateArray())
                    ReadJsonNumbers(values, $"{path}[{index++}]", item);
                break;
            case JsonValueKind.Number when value.TryGetDecimal(out var number):
                values[path] = number;
                break;
        }
    }

    private static void Add(IDictionary<string, decimal> values, string key, decimal? value)
    {
        if (value.HasValue) values[key] = value.Value;
    }
    private static void Add(IDictionary<string, decimal> values, string key, int? value)
    {
        if (value.HasValue) values[key] = value.Value;
    }
    private static decimal? ParseDecimal(string? value) => decimal.TryParse(value, NumberStyles.Number, CultureInfo.InvariantCulture, out var parsed) ? parsed : null;
    private static string NumericLabel(string key) => key.Replace("listing.", "出品: ", StringComparison.Ordinal)
        .Replace("item.", "アイテム: ", StringComparison.Ordinal)
        .Replace("equipment.", "装備: ", StringComparison.Ordinal)
        .Replace(".", " / ", StringComparison.Ordinal);
    private static bool Contains(string? source, string value) => source?.Contains(value, StringComparison.OrdinalIgnoreCase) == true;
    private static bool EqualsIgnoreCase(string? left, string? right) => string.Equals(left, right, StringComparison.OrdinalIgnoreCase);
}
