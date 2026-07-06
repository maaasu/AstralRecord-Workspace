using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface ILoginBonusClaimRepository
{
    Task<IReadOnlyList<LoginBonusClaimResponse>> GetByAccountIdAsync(Guid accountId, DateOnly? from, DateOnly? to);

    Task<LoginBonusClaimResponse> ClaimAsync(Guid accountId, LoginBonusClaimRequest request);
}
