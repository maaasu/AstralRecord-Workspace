using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IAccountWaystoneRepository
{
    Task<AccountWaystoneStateResponse> GetByAccountIdAsync(Guid accountId);

    Task<AccountWaystoneUnlockResponse> UnlockAsync(Guid accountId, AccountWaystoneUnlockRequest request);
}
