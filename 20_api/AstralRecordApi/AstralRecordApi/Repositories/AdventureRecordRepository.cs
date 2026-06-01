using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class AdventureRecordRepository(AstralRecordDbContext dbContext) : IAdventureRecordRepository
{
    public async Task<IReadOnlyList<AccountMobRecordResponse>> GetMobRecordsByAccountIdAsync(Guid accountId, string? category)
    {
        var normalizedCategory = NormalizeCategory(category);
        var query = dbContext.AccountMobRecords
            .AsNoTracking()
            .Where(record => record.AccountId == accountId && !record.IsDeleted);

        if (!string.IsNullOrWhiteSpace(normalizedCategory))
            query = query.Where(record => record.MobCategory == normalizedCategory);

        var records = await query
            .OrderByDescending(record => record.LastDefeatedAt)
            .ThenBy(record => record.MobId)
            .ToListAsync();

        return records.Select(Map).ToList();
    }

    public async Task<AccountMobRecordResponse> RecordMobDefeatAsync(AccountMobDefeatRequest request)
    {
        var now = DateTime.UtcNow;
        var mobId = request.MobId.Trim();
        var mobCategory = NormalizeCategory(request.MobCategory);
        var entity = await dbContext.AccountMobRecords
            .FirstOrDefaultAsync(record =>
                record.AccountId == request.AccountId
                && record.MobId == mobId
                && !record.IsDeleted);

        if (entity is null)
        {
            entity = new AccountMobRecordEntity
            {
                AccountMobRecordId = Guid.NewGuid(),
                AccountId = request.AccountId,
                MobId = mobId,
                MobCategory = mobCategory,
                DefeatCount = 1,
                FirstDefeatedAt = now,
                LastDefeatedAt = now,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = request.UpdatedBy,
                UpdatedBy = request.UpdatedBy,
                IsDeleted = false,
            };
            await dbContext.AccountMobRecords.AddAsync(entity);
        }
        else
        {
            entity.MobCategory = mobCategory;
            entity.DefeatCount += 1;
            entity.LastDefeatedAt = now;
            entity.UpdatedAt = now;
            entity.UpdatedBy = request.UpdatedBy;
        }

        await dbContext.SaveChangesAsync();
        return Map(entity);
    }

    private static string NormalizeCategory(string? category)
    {
        return string.IsNullOrWhiteSpace(category)
            ? "ENEMY"
            : category.Trim().ToUpperInvariant();
    }

    private static AccountMobRecordResponse Map(AccountMobRecordEntity entity) => new()
    {
        AccountMobRecordId = entity.AccountMobRecordId,
        AccountId = entity.AccountId,
        MobId = entity.MobId,
        MobCategory = entity.MobCategory,
        DefeatCount = entity.DefeatCount,
        FirstDefeatedAt = entity.FirstDefeatedAt,
        LastDefeatedAt = entity.LastDefeatedAt,
        CreatedAt = entity.CreatedAt,
        UpdatedAt = entity.UpdatedAt,
        CreatedBy = entity.CreatedBy,
        UpdatedBy = entity.UpdatedBy,
    };
}
