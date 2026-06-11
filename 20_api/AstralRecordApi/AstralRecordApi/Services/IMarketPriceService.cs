using AstralRecordApi.Models;

namespace AstralRecordApi.Services;

public interface IMarketPriceService
{
    Task<MarketPriceQuoteResponse?> CreateQuoteAsync(MarketPriceQuoteRequest request);
}
