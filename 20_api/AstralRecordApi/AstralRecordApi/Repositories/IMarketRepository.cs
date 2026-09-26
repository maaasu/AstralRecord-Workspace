using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IMarketRepository
{
    Task<IReadOnlyList<MarketListingResponse>> GetListingsAsync(MarketListingQuery query);

    Task<MarketTradeHistoryPageResponse> GetTradeHistoryAsync(MarketTradeHistoryQuery query);

    Task<MarketListingResponse?> GetListingAsync(Guid listingId);

    Task<MarketAccountSummaryResponse?> GetAccountSummaryAsync(Guid accountId);

    Task<MarketOperationResult<MarketListingResponse>> CreateListingAsync(MarketListingCreateRequest request);

    Task<MarketOperationResult<MarketListingResponse>> GetCreateListingResultAsync(
        Guid operationId,
        Guid sellerAccountId);

    Task<MarketOperationResult<MarketTransactionResponse>> PurchaseListingAsync(Guid listingId, MarketPurchaseRequest request);

    Task<MarketOperationResult<MarketWebPurchaseResponse>> CreateWebPurchaseAsync(
        Guid listingId, Guid actorUserUuid, MarketWebPurchaseRequest request);

    Task<MarketWebPurchaseResponse?> GetWebPurchaseAsync(Guid operationId, Guid actorUserUuid);

    Task<IReadOnlyList<MarketWebPurchaseResponse>> GetPendingWebPurchasesAsync(Guid buyerAccountId);

    Task RejectWebPurchaseAsync(Guid operationId, string errorCode);

    Task<MarketOperationResult<MarketListingResponse>> CancelListingAsync(Guid listingId, MarketCancelRequest request);

    Task<MarketOperationResult<MarketListingResponse>> GetCancelResultAsync(
        Guid listingId,
        Guid sellerAccountId,
        string idempotencyKey);

    Task<MarketOperationResult<MarketProceedsClaimResponse>> ClaimProceedsAsync(Guid listingId, MarketProceedsClaimRequest request);
}
