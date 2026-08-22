using AstralRecordApi.Services;
using Xunit;

namespace AstralRecordApi.Tests.Services;

public class MarketListingExpansionTests
{
    [Fact]
    public void ResolveSlotCount_CapsEachTokenTypeAndIgnoresUnknownEntries()
    {
        var slots = MarketListingExpansion.ResolveSlotCount(
        [
            (MarketListingExpansion.AlphaTokenItemId, 100L),
            (MarketListingExpansion.BetaTokenItemId, 2L),
            (MarketListingExpansion.GammaTokenItemId, 0L),
            (MarketListingExpansion.DeltaTokenItemId, -1L),
            ("unknown_token", 100L),
        ]);

        Assert.Equal(8, slots);
    }

    [Fact]
    public void ResolveSlotCount_SumsSplitEntriesBeforeApplyingPerTypeCap()
    {
        var slots = MarketListingExpansion.ResolveSlotCount(
        [
            (MarketListingExpansion.AlphaTokenItemId, 4L),
            (MarketListingExpansion.AlphaTokenItemId, 5L),
            (MarketListingExpansion.BetaTokenItemId, 2L),
        ]);

        Assert.Equal(8, slots);
    }

    [Fact]
    public void AddToBaseLimitAddsExpansionWithoutAllowingNegativeValues()
    {
        Assert.Equal(14, MarketListingExpansion.AddToBaseLimit(5, 9));
        Assert.Equal(5, MarketListingExpansion.AddToBaseLimit(5, -1));
        Assert.Equal(0, MarketListingExpansion.AddToBaseLimit(-1, -1));
    }
}
