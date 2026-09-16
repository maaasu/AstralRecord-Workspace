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
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
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
            Listing("astral_relic_two", 120, "{\"rollQualityScore\":70,\"damage\":18}"),
            Listing("astral_relic_three", 120, "{\"rollQualityScore\":95,\"damage\":24}"),
        ]);
        using var httpClient = new HttpClient(handler) { BaseAddress = new Uri("https://api.example/") };
        var page = CreatePage(new MarketApiClient(httpClient));

        await page.OnGetAsync(CancellationToken.None);
        var roll = Assert.Single(page.NumericFilters, filter => filter.Key == "listing.valuation.rollQualityScore");
        var damage = Assert.Single(page.NumericFilters, filter => filter.Key == "listing.valuation.damage");
        Assert.Equal("new_category", Assert.Single(page.Categories));

        page.PageContext.HttpContext.Request.QueryString = new QueryString(
            $"?{page.NumericMinimumName(roll)}=9e1&{page.NumericMaximumName(damage)}=20");
        await page.OnGetAsync(CancellationToken.None);

        var result = Assert.Single(page.Listings);
        Assert.Equal("astral_relic", result.Listing.ItemId);
    }

    [Fact]
    public async Task MarketPage_UsesEquipmentInstanceValuesForPurchaseDecisionFilters()
    {
        var equipment = new MarketEquipmentInstanceResponse
        {
            EquipmentInstanceId = Guid.NewGuid(),
            ItemId = "astral_blade",
            EnhanceLevel = 8,
            TranscendenceRank = 2,
            RuneMaxSlots = 3,
            DurabilityMax = 120,
            DurabilityValue = 91,
            StatRolls =
            [
                new MarketEquipmentStatRollResponse
                {
                    Status = "physical_attack",
                    Min = "18",
                    Max = "24",
                },
            ],
            Enchants =
            [
                new MarketEquipmentEnchantResponse
                {
                    Status = "critical_rate",
                    Type = "scalar",
                    Value = 0.04m,
                },
            ],
            Runes = [new MarketEquipmentRuneResponse { SlotIndex = 0, ItemId = "market_rune" }],
        };
        var handler = new MarketFixtureHandler(_ => [Listing("astral_blade", 500, equipment: equipment)]);
        using var httpClient = new HttpClient(handler) { BaseAddress = new Uri("https://api.example/") };
        var page = CreatePage(new MarketApiClient(httpClient));

        await page.OnGetAsync(CancellationToken.None);

        var listing = Assert.Single(page.Listings);
        Assert.NotNull(listing.EquipmentInstance);
        Assert.Equal(equipment.EquipmentInstanceId, listing.EquipmentInstance!.EquipmentInstanceId);
        Assert.Equal(8, listing.NumericAttributes["instance.enhance_level"]);
        Assert.Equal(18, listing.NumericAttributes["instance.stat.physical_attack.min"]);
        Assert.Equal(24, listing.NumericAttributes["instance.stat.physical_attack.max"]);
        Assert.Equal(0.04m, listing.NumericAttributes["instance.enchant.critical_rate.scalar"]);
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
    public async Task AnonymousMarketRequestRedirectsToLoginWithoutLoadingListings()
    {
        var handler = new MarketFixtureHandler(_ => []);
        await using var factory = new MarketFactory(handler);
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false });
        using var response = await client.GetAsync("/Market");
        Assert.Equal(HttpStatusCode.Found, response.StatusCode);
        Assert.Equal("/Login", response.Headers.Location?.AbsolutePath);
        Assert.Empty(handler.Requests);
    }

    [Fact]
    public async Task TimeoutShowsRetryMessageButCallerCancellationPropagates()
    {
        using var httpClient = new HttpClient(new CanceledResponseHandler()) { BaseAddress = new Uri("https://api.example/") };
        var page = CreatePage(new MarketApiClient(httpClient));
        await page.OnGetAsync(CancellationToken.None);
        Assert.Contains("タイムアウト", page.ErrorMessage);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => page.OnGetAsync(new CancellationToken(canceled: true)));
    }

    [Fact]
    public async Task ChangingCategoryKeepsAllCatalogOptionsAndAppliesPriceLocally()
    {
        var handler = new MarketFixtureHandler(_ => [Listing("sword", 120), Listing("map", 500)],
            id => id == "sword" ? "weapon" : "map");
        using var httpClient = new HttpClient(handler) { BaseAddress = new Uri("https://api.example/") };
        var page = CreatePage(new MarketApiClient(httpClient));
        page.Category = "weapon";
        await page.OnGetAsync(CancellationToken.None);
        Assert.Equal(2, page.Categories.Count);
        Assert.Equal("sword", Assert.Single(page.Listings).Listing.ItemId);
        page.Category = "map";
        await page.OnGetAsync(CancellationToken.None);
        Assert.Equal(2, page.Categories.Count);
        Assert.Equal("map", Assert.Single(page.Listings).Listing.ItemId);
        page.Category = null;
        page.MinimumPrice = 200;
        page.MaximumPrice = 600;
        await page.OnGetAsync(CancellationToken.None);
        Assert.Equal("map", Assert.Single(page.Listings).Listing.ItemId);
        Assert.DoesNotContain(handler.RequestUris, uri => uri.Contains("item_category=weapon", StringComparison.Ordinal));
    }

    private static IndexModel CreatePage(MarketApiClient client)
    {
        var page = new IndexModel(client);
        page.PageContext = new PageContext { HttpContext = new DefaultHttpContext() };
        return page;
    }

    private static MarketListingResponse Listing(
        string itemId,
        long price,
        string? valuation = null,
        MarketEquipmentInstanceResponse? equipment = null) => new()
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
        InstanceType = equipment is null ? null : "EQUIPMENT",
        InstanceId = equipment?.EquipmentInstanceId,
        EquipmentInstance = equipment,
        Status = "ACTIVE",
        ListedAt = DateTime.UtcNow,
        ExpiresAt = DateTime.UtcNow.AddDays(1),
    };

    private sealed class MarketFixtureHandler(Func<int, IReadOnlyList<MarketListingResponse>> listingsForPage, Func<string, string>? categoryForItem = null) : HttpMessageHandler
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
                    SchemaVersion = 1, Id = id, Category = categoryForItem?.Invoke(id) ?? "new_category", Name = id,
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

    private sealed class CanceledResponseHandler : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
            => Task.FromCanceled<HttpResponseMessage>(new CancellationToken(canceled: true));
    }

    private sealed class MarketFactory(MarketFixtureHandler handler) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder) => builder.ConfigureTestServices(services =>
            services.AddHttpClient<MarketApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler));
    }
}
