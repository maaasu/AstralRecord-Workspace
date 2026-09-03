using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IUserRepository
{
    Task<UserResponse?> GetByUuidAsync(Guid uuid);
    Task<UserResponse?> GetByMcidAsync(string mcid);
    Task<IReadOnlyList<string>> GetMcidsAsync(string? prefix);
    Task<bool> HasOtherByGlobalIpAsync(string globalIp, Guid excludingUuid);
    Task<UserResponse> CreateAsync(UserCreateRequest request);
    Task<UserResponse?> UpdateAsync(Guid uuid, UserUpdateRequest request);
    Task<UserHistoryResponse> CreateHistoryAsync(UserHistoryCreateRequest request);
}
