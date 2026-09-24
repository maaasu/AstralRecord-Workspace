using System.Net;
using System.Text;
using System.Text.Json;
using AstralRecordWeb.Models;
using AstralRecordWeb.Pages.Market;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.AspNetCore.WebUtilities;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace AstralRecordWeb.Tests;

public sealed class MarketTradeHistoryTests
{
    [Fact]
    public async Task MarketClient_UsesPageResponseAndKeepsFiltersForPaging()
    {
        var handler = new TradeHistoryHandler(Enumerable.Range(0, 21)
            .Select(index => Transaction($"item_{index}", index + 1)).ToArray());
        using var httpClient = new HttpClient(handler) { BaseAddress = new Uri("https://api.example/") };
        var client = new MarketApiClient(httpClient);

        var result = await client.GetTradeHistoryAsync(" material ", "item/with space", 3, CancellationToken.None);

        Assert.Equal(20, result.Items.Count);
        Assert.True(result.HasNextPage);
        var query = QueryHelpers.ParseQuery(handler.TransactionRequest!.Query);
        Assert.Equal("3", query["page"]);
        Assert.Equal("20", query["page_size"]);
        Assert.Equal("material", query["item_category"]);
        Assert.Equal("item/with space", query["item_id"]);
        Assert.Equal("item_0", result.Items[0].DisplayName);
        Assert.Equal("market-seller", result.Items[0].SellerDisplayName);
        Assert.Equal("market-buyer", result.Items[0].BuyerDisplayName);
    }

    [Fact]
    public async Task HistoryPage_ShowsTransactionsAndPreservesSearchInPageLinks()
    {
        var handler = new TradeHistoryHandler([Transaction("astral_ore", 120)]);
        using var httpClient = new HttpClient(handler) { BaseAddress = new Uri("https://api.example/") };
        var page = CreatePage(new MarketApiClient(httpClient));
        page.Category = " material ";
        page.ItemId = " astral_ore ";
        page.PageNumber = 2;

        await page.OnGetAsync(CancellationToken.None);

        var transaction = Assert.Single(page.Transactions);
        Assert.Equal("astral_ore", transaction.Transaction.ItemId);
        Assert.Null(page.ErrorMessage);
        var query = QueryHelpers.ParseQuery(new Uri("https://example.test" + page.PageLink(3)).Query);
        Assert.Equal("material", query[nameof(page.Category)]);
        Assert.Equal("astral_ore", query[nameof(page.ItemId)]);
        Assert.Equal("3", query[nameof(page.PageNumber)]);
    }

    [Fact]
    public async Task HistoryPage_RejectsInvalidPageWithoutCallingApi()
    {
        var handler = new TradeHistoryHandler([]);
        using var httpClient = new HttpClient(handler) { BaseAddress = new Uri("https://api.example/") };
        var page = CreatePage(new MarketApiClient(httpClient));
        page.PageNumber = 0;

        await page.OnGetAsync(CancellationToken.None);

        Assert.NotNull(page.ErrorMessage);
        Assert.Null(handler.TransactionRequest);
    }

    [Fact]
    public async Task EmptyLaterPage_KeepsPreviousPageNavigationAvailable()
    {
        var handler = new TradeHistoryHandler([]);
        using var httpClient = new HttpClient(handler) { BaseAddress = new Uri("https://api.example/") };
        var page = CreatePage(new MarketApiClient(httpClient));
        page.Category = "material";
        page.PageNumber = 2;

        await page.OnGetAsync(CancellationToken.None);

        Assert.Empty(page.Transactions);
        Assert.True(page.ShowPagination);
        var query = QueryHelpers.ParseQuery(new Uri("https://example.test" + page.PageLink(1)).Query);
        Assert.Equal("material", query[nameof(page.Category)]);
        Assert.Equal("1", query[nameof(page.PageNumber)]);
    }

    [Fact]
    public async Task AnonymousHistoryRequestRedirectsToLoginWithoutLoadingHistory()
    {
        var handler = new TradeHistoryHandler([]);
        await using var factory = new MarketHistoryFactory(handler);
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions
        {
            BaseAddress = new Uri("https://localhost"),
            AllowAutoRedirect = false,
        });

        using var response = await client.GetAsync("/Market/History");

        Assert.Equal(HttpStatusCode.Found, response.StatusCode);
        Assert.Equal("/Login", response.Headers.Location?.AbsolutePath);
        Assert.Null(handler.TransactionRequest);
    }

    private static HistoryModel CreatePage(MarketApiClient client)
    {
        var page = new HistoryModel(client);
        page.PageContext = new PageContext
        {
            HttpContext = new DefaultHttpContext
            {
                Request = { Path = "/Market/History" },
            },
        };
        return page;
    }

    private static MarketTradeHistoryResponse Transaction(string itemId, long price) => new()
    {
        TransactionId = Guid.NewGuid(),
        SellerAccountId = Guid.NewGuid(),
        SellerAccountName = "market-seller",
        BuyerAccountId = Guid.NewGuid(),
        BuyerAccountName = "market-buyer",
        ItemCategory = "material",
        ItemId = itemId,
        Quantity = 1,
        CurrencyId = "gold",
        UnitPrice = price,
        TotalPrice = price,
        CompletedAt = DateTime.UtcNow,
    };

    private sealed class TradeHistoryHandler(IReadOnlyList<MarketTradeHistoryResponse> transactions) : HttpMessageHandler
    {
        private readonly JsonSerializerOptions serializerOptions = new(JsonSerializerDefaults.Web);
        public Uri? TransactionRequest { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            if (request.RequestUri!.AbsolutePath == "/api/market/transactions")
            {
                TransactionRequest = request.RequestUri;
                return Task.FromResult(Json(new MarketTradeHistoryPageResponse
                {
                    Items = transactions.Take(MarketApiClient.TradeHistoryPageSize).ToArray(),
                    HasNextPage = transactions.Count > MarketApiClient.TradeHistoryPageSize,
                }));
            }

            if (request.RequestUri.AbsolutePath.StartsWith("/api/item/", StringComparison.Ordinal))
            {
                var itemId = Uri.UnescapeDataString(request.RequestUri.AbsolutePath["/api/item/".Length..]);
                return Task.FromResult(Json(new ItemMasterResponse
                {
                    SchemaVersion = 1,
                    Id = itemId,
                    Category = "material",
                    Name = itemId,
                    Icon = "NETHER_STAR",
                    Rarity = "COMMON",
                }));
            }

            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        }

        private HttpResponseMessage Json<T>(T value) => new(HttpStatusCode.OK)
        {
            Content = new StringContent(JsonSerializer.Serialize(value, serializerOptions), Encoding.UTF8, "application/json"),
        };
    }

    private sealed class MarketHistoryFactory(TradeHistoryHandler handler) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder) => builder.ConfigureTestServices(services =>
            services.AddHttpClient<MarketApiClient>().ConfigurePrimaryHttpMessageHandler(() => handler));
    }
}
