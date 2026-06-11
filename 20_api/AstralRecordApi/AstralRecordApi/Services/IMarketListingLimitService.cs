using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;

namespace AstralRecordApi.Services;

public interface IMarketListingLimitService
{
    MarketAccountSummaryResponse BuildSummary(MarketAccountStateEntity state, int activeListingCount);

    (string Tier, int MaxActiveListingCount) ResolveLimit(int completedTradeCount);
}
