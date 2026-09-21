using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IAccountRepository
{
    Task<IReadOnlyList<AccountResponse>> GetByUserIdAsync(Guid userId);
    Task<AccountResponse?> GetByUuidAsync(Guid uuid);
    Task<AccountResponse?> ResolveAsync(string? selector, string? userMcid);
    Task<AccountResponse> CreateAsync(AccountCreateRequest request);
    Task<AccountCloneResponse?> CloneAsync(Guid sourceUuid, AccountCloneRequest request);
    Task<AccountResponse?> UpdateAsync(Guid uuid, AccountUpdateRequest request);
    Task<AccountDeleteResponse?> DeleteAsync(Guid uuid, AccountDeleteRequest request);
}
