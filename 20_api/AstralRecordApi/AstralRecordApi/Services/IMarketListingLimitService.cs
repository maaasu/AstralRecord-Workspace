using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;

namespace AstralRecordApi.Services;

public interface IMarketListingLimitService
{
    MarketAccountSummaryResponse BuildSummary(
        MarketAccountStateEntity state,
        int activeListingCount,
        int usedListingSlotCount);

    (string Tier, int MaxActiveListingCount) ResolveLimit(int completedTradeCount);
}
