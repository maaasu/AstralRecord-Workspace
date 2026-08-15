using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface ITradeRepository
{
    Task<TradeOperationResult<TradeCommitResponse>> CommitAsync(TradeCommitRequest request);
}
