using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IAccountQuestStateRepository
{
    Task<AccountQuestStateResponse> GetByAccountIdAsync(Guid accountId);
    Task<AccountQuestStateResponse> UpsertAsync(Guid accountId, AccountQuestStateUpsertRequest request);
}
