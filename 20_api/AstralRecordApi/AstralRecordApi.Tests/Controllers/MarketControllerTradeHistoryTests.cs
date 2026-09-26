using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public sealed class MarketControllerTradeHistoryTests
{
    [Fact]
    public async Task WebPurchase_WhenWebCredentialIsMissing_ReturnsServiceUnavailable()
    {
        var controller = new MarketWebPurchaseController(
            new RecordingMarketRepository(), new ConfigurationBuilder().Build())
        {
            ControllerContext = new ControllerContext { HttpContext = new DefaultHttpContext() },
        };

        var response = await controller.Create(Guid.NewGuid(), Guid.NewGuid(),
            new MarketWebPurchaseRequest { OperationId = Guid.NewGuid(), BuyerAccountId = Guid.NewGuid() });

        Assert.Equal(503, Assert.IsType<ObjectResult>(response).StatusCode);
    }
    [Fact]
    public async Task GetTradeHistory_TrimsFiltersAndReturnsRepositoryResult()
    {
        var repository = new RecordingMarketRepository();
        var controller = new MarketController(repository, new UnusedPriceService());

        var result = await controller.GetTradeHistory(" material ", " astral_ore ", 2, 25);

        var ok = Assert.IsType<OkObjectResult>(result);
        Assert.Same(repository.Result, ok.Value);
        Assert.Equal("material", repository.Query!.ItemCategory);
        Assert.Equal("astral_ore", repository.Query.ItemId);
        Assert.Equal(2, repository.Query.Page);
        Assert.Equal(25, repository.Query.PageSize);
    }

    [Theory]
    [InlineData(0, 20)]
    [InlineData(1, 0)]
    [InlineData(100001, 20)]
    [InlineData(1, 101)]
    public async Task GetTradeHistory_RejectsInvalidPaging(int page, int pageSize)
    {
        var repository = new RecordingMarketRepository();
        var controller = new MarketController(repository, new UnusedPriceService());

        var result = await controller.GetTradeHistory(null, null, page, pageSize);

        var problem = Assert.IsType<ObjectResult>(result);
        Assert.Equal(400, problem.StatusCode);
        Assert.Null(repository.Query);
    }

    private sealed class RecordingMarketRepository : IMarketRepository
    {
        public MarketTradeHistoryQuery? Query { get; private set; }
        public MarketTradeHistoryPageResponse Result { get; } = new()
        {
            Items =
            [
                new()
                {
                    TransactionId = Guid.NewGuid(), ItemCategory = "material", ItemId = "astral_ore",
                    Quantity = 1, CurrencyId = "gold", UnitPrice = 100, TotalPrice = 100,
                    CompletedAt = DateTime.UtcNow,
                },
            ],
            HasNextPage = true,
        };

        public Task<MarketTradeHistoryPageResponse> GetTradeHistoryAsync(MarketTradeHistoryQuery query)
        {
            Query = query;
            return Task.FromResult(Result);
        }

        public Task<IReadOnlyList<MarketListingResponse>> GetListingsAsync(MarketListingQuery query) => throw new NotSupportedException();
        public Task<MarketListingResponse?> GetListingAsync(Guid listingId) => throw new NotSupportedException();
        public Task<MarketAccountSummaryResponse?> GetAccountSummaryAsync(Guid accountId) => throw new NotSupportedException();
        public Task<MarketOperationResult<MarketListingResponse>> CreateListingAsync(MarketListingCreateRequest request) => throw new NotSupportedException();
        public Task<MarketOperationResult<MarketListingResponse>> GetCreateListingResultAsync(Guid operationId, Guid sellerAccountId) => throw new NotSupportedException();
        public Task<MarketOperationResult<MarketTransactionResponse>> PurchaseListingAsync(Guid listingId, MarketPurchaseRequest request) => throw new NotSupportedException();
        public Task<MarketOperationResult<MarketWebPurchaseResponse>> CreateWebPurchaseAsync(Guid listingId, Guid actorUserUuid, MarketWebPurchaseRequest request) => throw new NotSupportedException();
        public Task<MarketWebPurchaseResponse?> GetWebPurchaseAsync(Guid operationId, Guid actorUserUuid) => throw new NotSupportedException();
        public Task<IReadOnlyList<MarketWebPurchaseResponse>> GetPendingWebPurchasesAsync(Guid buyerAccountId) => throw new NotSupportedException();
        public Task RejectWebPurchaseAsync(Guid operationId, string errorCode) => throw new NotSupportedException();
        public Task<MarketOperationResult<MarketListingResponse>> CancelListingAsync(Guid listingId, MarketCancelRequest request) => throw new NotSupportedException();
        public Task<MarketOperationResult<MarketListingResponse>> GetCancelResultAsync(Guid listingId, Guid sellerAccountId, string idempotencyKey) => throw new NotSupportedException();
        public Task<MarketOperationResult<MarketProceedsClaimResponse>> ClaimProceedsAsync(Guid listingId, MarketProceedsClaimRequest request) => throw new NotSupportedException();
    }

    private sealed class UnusedPriceService : IMarketPriceService
    {
        public Task<MarketPriceQuoteResponse?> CreateQuoteAsync(MarketPriceQuoteRequest request) => throw new NotSupportedException();
    }
}
