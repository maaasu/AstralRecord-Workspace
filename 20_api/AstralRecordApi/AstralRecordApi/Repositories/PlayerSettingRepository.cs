using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class PlayerSettingRepository(AstralRecordDbContext dbContext) : IPlayerSettingRepository
{
    public async Task<IReadOnlyList<PlayerSettingResponse>> GetByUserIdAsync(Guid userId)
    {
        var settings = await dbContext.PlayerSettings
            .AsNoTracking()
            .Where(x => x.UserId == userId && !x.IsDeleted)
            .OrderBy(x => x.SettingKey)
            .ToListAsync();

        return settings.Select(MapToResponse).ToList();
    }

    public async Task<PlayerSettingResponse?> GetByIdAsync(Guid userSettingId)
    {
        var setting = await dbContext.PlayerSettings
            .AsNoTracking()
            .FirstOrDefaultAsync(x => x.UserSettingId == userSettingId && !x.IsDeleted);

        return setting is null ? null : MapToResponse(setting);
    }

    public async Task<PlayerSettingResponse> CreateAsync(PlayerSettingCreateRequest request)
    {
        var now = DateTime.UtcNow;
        var entity = new PlayerSettingEntity
        {
            UserSettingId = Guid.NewGuid(),
            UserId = request.UserId,
            SettingKey = request.SettingKey,
            SettingValueJson = request.SettingValueJson,
            Version = 1,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = request.CreatedBy,
            UpdatedBy = request.CreatedBy,
            IsDeleted = false,
        };

        await dbContext.PlayerSettings.AddAsync(entity);
        await dbContext.SaveChangesAsync();

        return MapToResponse(entity);
    }

    public async Task<PlayerSettingUpdateResult?> UpdateAsync(Guid userSettingId, PlayerSettingUpdateRequest request)
    {
        var entity = await dbContext.PlayerSettings
            .FirstOrDefaultAsync(x => x.UserSettingId == userSettingId && !x.IsDeleted);

        if (entity is null)
            return null;

        if (entity.Version != request.ExpectedVersion)
        {
            return new PlayerSettingUpdateResult
            {
                IsVersionConflict = true,
                Current = MapToResponse(entity),
            };
        }

        entity.SettingValueJson = request.SettingValueJson;
        entity.Version += 1;
        entity.UpdatedAt = DateTime.UtcNow;
        entity.UpdatedBy = request.UpdatedBy;

        await dbContext.SaveChangesAsync();

        return new PlayerSettingUpdateResult
        {
            Updated = MapToResponse(entity),
        };
    }

    private static PlayerSettingResponse MapToResponse(PlayerSettingEntity entity) => new()
    {
        UserSettingId = entity.UserSettingId,
        UserId = entity.UserId,
        SettingKey = entity.SettingKey,
        SettingValueJson = entity.SettingValueJson,
        Version = entity.Version,
        CreatedAt = entity.CreatedAt,
        UpdatedAt = entity.UpdatedAt,
        CreatedBy = entity.CreatedBy,
        UpdatedBy = entity.UpdatedBy,
        IsDeleted = entity.IsDeleted,
    };
}
