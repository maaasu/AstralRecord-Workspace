using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IAccountGuideProgressRepository
{
    Task<AccountGuideProgressResponse> GetByAccountIdAsync(Guid accountId);

    Task<AccountGuideStepProgressResponse> CompleteStepAsync(
        Guid accountId,
        AccountGuideStepCompleteRequest request);
}
