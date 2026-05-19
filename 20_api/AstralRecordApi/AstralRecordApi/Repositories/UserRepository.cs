using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class UserRepository(AstralRecordDbContext dbContext) : IUserRepository
{
    public async Task<UserResponse?> GetByUuidAsync(Guid uuid)
    {
        var user = await dbContext.Users
            .AsNoTracking()
            .FirstOrDefaultAsync(x => x.Uuid == uuid && !x.IsDeleted);

        return user is null ? null : MapToResponse(user);
    }

    public async Task<UserResponse> CreateAsync(UserCreateRequest request)
    {
        var now = DateTime.UtcNow;
        var user = new UserEntity
        {
            Uuid = request.Uuid,
            Mcid = request.Mcid,
            JoinDate = request.JoinDate,
            LastJoinDate = request.LastJoinDate,
            GlobalIp = request.GlobalIp,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = request.CreatedBy,
            UpdatedBy = request.CreatedBy,
        };

        await dbContext.Users.AddAsync(user);
        await dbContext.SaveChangesAsync();

        return MapToResponse(user);
    }

    public async Task<UserResponse?> UpdateAsync(Guid uuid, UserUpdateRequest request)
    {
        var user = await dbContext.Users
            .FirstOrDefaultAsync(x => x.Uuid == uuid && !x.IsDeleted);

        if (user is null)
            return null;

        if (request.Mcid is not null)
            user.Mcid = request.Mcid;

        if (request.LastJoinDate.HasValue)
            user.LastJoinDate = request.LastJoinDate.Value;

        if (request.GlobalIp is not null)
            user.GlobalIp = request.GlobalIp;

        if (request.AccountId.HasValue)
            user.AccountId = request.AccountId.Value;

        if (request.BanIndefinite.HasValue)
            user.BanIndefinite = request.BanIndefinite.Value;

        if (request.BanDate.HasValue)
            user.BanDate = request.BanDate.Value;

        if (request.KickIp.HasValue)
            user.KickIp = request.KickIp.Value;

        if (request.Permission.HasValue)
            user.Permission = request.Permission.Value;

        user.UpdatedAt = DateTime.UtcNow;
        user.UpdatedBy = request.UpdatedBy;

        await dbContext.SaveChangesAsync();

        return MapToResponse(user);
    }

    private static UserResponse MapToResponse(UserEntity user) => new()
    {
        Uuid = user.Uuid,
        Mcid = user.Mcid,
        JoinDate = user.JoinDate,
        LastJoinDate = user.LastJoinDate,
        GlobalIp = user.GlobalIp,
        AccountId = user.AccountId,
        BanIndefinite = user.BanIndefinite,
        BanDate = user.BanDate,
        KickIp = user.KickIp,
        Permission = user.Permission,
        CreatedAt = user.CreatedAt,
        UpdatedAt = user.UpdatedAt,
        CreatedBy = user.CreatedBy,
        UpdatedBy = user.UpdatedBy,
        IsDeleted = user.IsDeleted,
    };
}
