using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IPlayerSettingRepository
{
    Task<IReadOnlyList<PlayerSettingResponse>> GetByUserIdAsync(Guid userId);
    Task<PlayerSettingResponse?> GetByIdAsync(Guid userSettingId);
    Task<PlayerSettingResponse> CreateAsync(PlayerSettingCreateRequest request);
    Task<PlayerSettingUpdateResult?> UpdateAsync(Guid userSettingId, PlayerSettingUpdateRequest request);
}
