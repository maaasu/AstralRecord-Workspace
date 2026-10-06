using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IAccountGuideCompletionRewardRepository
{
    Task<AccountGuideCompletionRewardResponse> GetAsync(Guid accountId);
    Task<AccountGuideCompletionRewardResponse> ClaimAsync(Guid accountId, Guid updatedBy);
}
