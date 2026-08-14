using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;

namespace AstralRecordApi.Services;

public class MarketListingLimitService : IMarketListingLimitService
{
    public MarketAccountSummaryResponse BuildSummary(
        MarketAccountStateEntity state,
        int activeListingCount,
        int usedListingSlotCount)
    {
        var limit = ResolveLimit(state.CompletedTradeCount);
        return new MarketAccountSummaryResponse
        {
            AccountId = state.AccountId,
            ActiveListingCount = activeListingCount,
            MaxActiveListingCount = limit.MaxActiveListingCount,
            UsedListingSlotCount = usedListingSlotCount,
            MaxListingSlotCount = limit.MaxActiveListingCount,
            CompletedTradeCount = state.CompletedTradeCount,
            Tier = limit.Tier,
            SuspendedUntil = state.SuspendedUntil,
            UpdatedAt = state.UpdatedAt,
        };
    }

    public (string Tier, int MaxActiveListingCount) ResolveLimit(int completedTradeCount)
    {
        return completedTradeCount switch
        {
            >= 100 => ("T4", 20),
            >= 50 => ("T3", 12),
            >= 20 => ("T2", 8),
            >= 5 => ("T1", 5),
            _ => ("T0", 3),
        };
    }
}
