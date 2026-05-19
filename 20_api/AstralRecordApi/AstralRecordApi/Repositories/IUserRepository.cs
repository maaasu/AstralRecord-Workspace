using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IUserRepository
{
    Task<UserResponse?> GetByUuidAsync(Guid uuid);
    Task<UserResponse> CreateAsync(UserCreateRequest request);
    Task<UserResponse?> UpdateAsync(Guid uuid, UserUpdateRequest request);
}
