using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IAdventureRecordRepository
{
    Task<IReadOnlyList<AccountMobRecordResponse>> GetMobRecordsByAccountIdAsync(Guid accountId, string? category);

    Task<AccountMobRecordResponse> RecordMobDefeatAsync(AccountMobDefeatRequest request);
}
