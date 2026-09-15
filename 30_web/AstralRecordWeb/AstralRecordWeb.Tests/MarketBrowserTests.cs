using System.Net;
using System.Text;
using System.Text.Json;
using AstralRecordWeb.Models;
using AstralRecordWeb.Pages.Market;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.AspNetCore.WebUtilities;
using Xunit;

namespace AstralRecordWeb.Tests;

public sealed class MarketBrowserTests
{
    [Fact]
    public async Task MarketClient_ReadsEveryPageUntilTheShortTerminalPage()
    {
        var handler = new MarketFixtureHandler(page => page == 1
            ? Enumerable.Range(0, 100).Select(index => Listing($"item_{index}", 100 + index)).ToArray()
            : [Listing("terminal_item", 999)]);
        using var httpClient = new HttpClient(handler) { BaseAddress = new Uri("https://api.example/") };
        var client = new MarketApiClient(httpClient);

        var listings = await client.GetActiveListingsAsync(null, null, null, "price_asc", CancellationToken.None);

        Assert.Equal(101, listings.Count);
        Assert.Equal(2, handler.ListingRequests);
        Assert.All(handler.Requests, request => Assert.Equal(HttpMethod.Get, request.Method));
        Assert.Contains(handler.RequestUris, uri => uri.Contains("page=2", StringComparison.Ordinal));
        Assert.Contains(handler.RequestUris, uri => uri.Contains("sort=price_asc", StringComparison.Ordinal));
    }

    [Fact]
    public async Task MarketPage_DynamicallySupportsUnknownCategoryAndMultipleListingNumericFilters()
    {
        var handler = new MarketFixtureHandler(_ =>
        [
            Listing("astral_relic", 120, "{\"rollQualityScore\":95,\"damage\":18}"),
            Listing("astral_relic_two", 120, "{\"rollQualityScore\":70,\"damage\":24}"),
        ]);
        using var httpClient = new HttpClient(handler) { BaseAddress = new Uri("https://api.example/") };
        var page = CreatePage(new MarketApiClient(httpClient));

        await page.OnGetAsync(CancellationToken.None);
        var roll = Assert.Single(page.NumericFilters, filter => filter.Key == "listing.valuation.rollQualityScore");
        var damage = Assert.Single(page.NumericFilters, filter => filter.Key == "listing.valuation.damage");
        Assert.Equal("new_category", Assert.Single(page.Categories));

        page.PageContext.HttpContext.Request.QueryString = new QueryString(
            $"?{page.NumericMinimumName(roll)}=90&{page.NumericMaximumName(damage)}=20");
        await page.OnGetAsync(CancellationToken.None);

        var result = Assert.Single(page.Listings);
        Assert.Equal("astral_relic", result.Listing.ItemId);
    }

    [Fact]
    public async Task MarketPage_ShowsRetrievalErrorWithoutSubstitutingListings()
    {
        using var httpClient = new HttpClient(new FixedResponseHandler(HttpStatusCode.ServiceUnavailable))
        {
            BaseAddress = new Uri("https://api.example/"),
        };
        var page = CreatePage(new MarketApiClient(httpClient));

        await page.OnGetAsync(CancellationToken.None);

        Assert.NotNull(page.ErrorMessage);
        Assert.Empty(page.Listings);
        Assert.Equal(0, page.TotalListingCount);
    }

    [Fact]
    public void MarketPage_RequiresLoginAndDefinesNoPostAction()
    {
        Assert.NotEmpty(typeof(IndexModel).GetCustomAttributes(typeof(AuthorizeAttribute), inherit: true));
        Assert.DoesNotContain(typeof(IndexModel).GetMethods(), method => method.Name.StartsWith("OnPost", StringComparison.Ordinal));
    }

    private static IndexModel CreatePage(MarketApiClient client)
    {
        var page = new IndexModel(client);
        page.PageContext = new PageContext { HttpContext = new DefaultHttpContext() };
        return page;
    }

    private static MarketListingResponse Listing(string itemId, long price, string? valuation = null) => new()
    {
        ListingId = Guid.NewGuid(),
        SellerAccountId = Guid.NewGuid(),
        SellerAccountName = "tester",
        ItemCategory = "new_category",
        ItemId = itemId,
        Quantity = 1,
        RemainingQuantity = 1,
        CurrencyId = "gold",
        UnitPrice = price,
        TotalPrice = price,
        PriceFloor = 1,
        ValuationSnapshotJson = valuation,
        Status = "ACTIVE",
        ListedAt = DateTime.UtcNow,
        ExpiresAt = DateTime.UtcNow.AddDays(1),
    };

    private sealed class MarketFixtureHandler(Func<int, IReadOnlyList<MarketListingResponse>> listingsForPage) : HttpMessageHandler
    {
        private readonly JsonSerializerOptions serializerOptions = new(JsonSerializerDefaults.Web);
        public int ListingRequests { get; private set; }
        public List<HttpRequestMessage> Requests { get; } = [];
        public List<string> RequestUris { get; } = [];

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Requests.Add(request);
            RequestUris.Add(request.RequestUri!.Query);
            if (request.RequestUri.AbsolutePath == "/api/market/listings")
            {
                ListingRequests++;
                var page = int.Parse(QueryHelpers.ParseQuery(request.RequestUri.Query)["page"].FirstOrDefault() ?? "1");
                return Task.FromResult(Json(listingsForPage(page)));
            }

            if (request.RequestUri.AbsolutePath.StartsWith("/api/item/", StringComparison.Ordinal))
            {
                var id = Uri.UnescapeDataString(request.RequestUri.AbsolutePath["/api/item/".Length..]);
                return Task.FromResult(Json(new ItemMasterResponse
                {
                    SchemaVersion = 1, Id = id, Category = "new_category", Name = id,
                    Icon = "NETHER_STAR", Rarity = "MYSTIC",
                }));
            }

            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        }

        private HttpResponseMessage Json<T>(T value) => new(HttpStatusCode.OK)
        {
            Content = new StringContent(JsonSerializer.Serialize(value, serializerOptions), Encoding.UTF8, "application/json"),
        };
    }

    private sealed class FixedResponseHandler(HttpStatusCode statusCode) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
            => Task.FromResult(new HttpResponseMessage(statusCode));
    }
}
