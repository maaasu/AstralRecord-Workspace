using System.Net;
using System.Net.Http.Json;
using AstralRecordWeb.Models;

namespace AstralRecordWeb.Services;

/// <summary>マーケットの参照専用 API クライアントです。変更系 endpoint は公開しません。</summary>
public sealed class MarketApiClient(HttpClient httpClient)
{
    private const int ListingPageSize = 100;
    public const int TradeHistoryPageSize = 20;

    public async Task<IReadOnlyList<MarketListingResponse>> GetActiveListingsAsync(
        string? category,
        long? minimumPrice,
        long? maximumPrice,
        string? sort,
        CancellationToken cancellationToken)
    {
        var listings = new List<MarketListingResponse>();
        var seenListingIds = new HashSet<Guid>();
        for (var page = 1; ; page++)
        {
            var query = new List<string>
            {
                "status=ACTIVE",
                $"page={page}",
                $"page_size={ListingPageSize}",
            };

            if (!string.IsNullOrWhiteSpace(category))
                query.Add($"item_category={Uri.EscapeDataString(category)}");
            if (minimumPrice.HasValue)
                query.Add($"min_price={minimumPrice.Value}");
            if (maximumPrice.HasValue)
                query.Add($"max_price={maximumPrice.Value}");
            if (!string.IsNullOrWhiteSpace(sort))
                query.Add($"sort={Uri.EscapeDataString(sort)}");

            var pageListings = await httpClient.GetFromJsonAsync<IReadOnlyList<MarketListingResponse>>(
                $"api/market/listings?{string.Join('&', query)}",
                cancellationToken) ?? [];

            foreach (var listing in pageListings)
            {
                if (seenListingIds.Add(listing.ListingId))
                    listings.Add(listing);
            }
            if (pageListings.Count < ListingPageSize)
                return listings;
        }
    }

    public async Task<IReadOnlyDictionary<string, ItemMasterResponse>> GetItemsAsync(
        IEnumerable<string> itemIds,
        CancellationToken cancellationToken)
    {
        var distinctIds = itemIds
            .Where(itemId => !string.IsNullOrWhiteSpace(itemId))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();

        var results = await Task.WhenAll(distinctIds.Select(async itemId =>
        {
            using var response = await httpClient.GetAsync(
                $"api/item/{Uri.EscapeDataString(itemId)}", cancellationToken);
            if (response.StatusCode == HttpStatusCode.NotFound)
                return (ItemId: itemId, Item: (ItemMasterResponse?)null);

            response.EnsureSuccessStatusCode();
            return (ItemId: itemId, Item: await response.Content.ReadFromJsonAsync<ItemMasterResponse>(cancellationToken));
        }));

        return results
            .Where(result => result.Item is not null)
            .ToDictionary(result => result.ItemId, result => result.Item!, StringComparer.OrdinalIgnoreCase);
    }

    public async Task<MarketTradeHistoryPage> GetTradeHistoryAsync(
        string? category,
        string? itemId,
        int page,
        CancellationToken cancellationToken)
    {
        var query = new List<string>
        {
            $"page={page}",
            $"page_size={TradeHistoryPageSize}",
        };
        if (!string.IsNullOrWhiteSpace(category))
            query.Add($"item_category={Uri.EscapeDataString(category.Trim())}");
        if (!string.IsNullOrWhiteSpace(itemId))
            query.Add($"item_id={Uri.EscapeDataString(itemId.Trim())}");

        var response = await httpClient.GetFromJsonAsync<MarketTradeHistoryPageResponse>(
            $"api/market/transactions?{string.Join('&', query)}",
            cancellationToken) ?? new MarketTradeHistoryPageResponse();
        var transactions = response.Items;
        var items = await GetItemsAsync(transactions.Select(transaction => transaction.ItemId), cancellationToken);

        return new MarketTradeHistoryPage(
            transactions.Select(transaction => new MarketTradeHistoryItem(
                transaction,
                items.GetValueOrDefault(transaction.ItemId))).ToArray(),
            response.HasNextPage);
    }
}
