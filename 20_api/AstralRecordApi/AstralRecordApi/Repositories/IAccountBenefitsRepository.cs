using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IAccountBenefitsRepository
{
    Task<AccountBenefitsResponse?> GetAsync(Guid accountId);
    Task<IReadOnlyList<VipSupporterResponse>> ListSupportersAsync();
    Task<AccountBenefitOperationResponse?> ExecuteAsync(Guid accountId, string kind, AccountBenefitOperationRequest request);
}
