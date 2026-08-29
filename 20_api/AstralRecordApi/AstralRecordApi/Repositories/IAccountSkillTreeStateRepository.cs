using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IAccountSkillTreeStateRepository
{
    Task<AccountSkillTreeStateResponse> GetByAccountIdAsync(Guid accountId);

    Task<AccountSkillTreeStateResponse> UpsertAsync(Guid accountId, AccountSkillTreeStateUpsertRequest request);

    Task<AccountSkillTreeStateResponse> RepairInvalidStateAsync(
        Guid accountId,
        AccountSkillTreeInvalidStateRepairRequest request);
}
