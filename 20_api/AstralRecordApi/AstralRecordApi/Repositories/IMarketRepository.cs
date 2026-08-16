using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IMarketRepository
{
    Task<IReadOnlyList<MarketListingResponse>> GetListingsAsync(MarketListingQuery query);

    Task<MarketListingResponse?> GetListingAsync(Guid listingId);

    Task<MarketAccountSummaryResponse?> GetAccountSummaryAsync(Guid accountId);

    Task<MarketOperationResult<MarketListingResponse>> CreateListingAsync(MarketListingCreateRequest request);

    Task<MarketOperationResult<MarketTransactionResponse>> PurchaseListingAsync(Guid listingId, MarketPurchaseRequest request);

    Task<MarketOperationResult<MarketListingResponse>> CancelListingAsync(Guid listingId, MarketCancelRequest request);

    Task<MarketOperationResult<MarketProceedsClaimResponse>> ClaimProceedsAsync(Guid listingId, MarketProceedsClaimRequest request);
}
