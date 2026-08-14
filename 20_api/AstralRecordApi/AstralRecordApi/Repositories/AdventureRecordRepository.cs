using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;
using System.Data;

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

    public async Task<IReadOnlyList<AccountDungeonRecordResponse>> GetDungeonRecordsByAccountIdAsync(
        Guid accountId)
    {
        var records = await dbContext.AccountDungeonRecords
            .AsNoTracking()
            .Where(record => record.AccountId == accountId && !record.IsDeleted)
            .OrderByDescending(record => record.LastClearedAt)
            .ThenBy(record => record.DungeonId)
            .ToListAsync();

        return records.Select(Map).ToList();
    }

    public async Task<AccountDungeonRecordResponse> RecordDungeonClearAsync(
        AccountDungeonClearRequest request)
    {
        if (request.AccountId == Guid.Empty)
            throw new ArgumentException("AccountId must not be empty.", nameof(request));
        if (request.UpdatedBy == Guid.Empty)
            throw new ArgumentException("UpdatedBy must not be empty.", nameof(request));
        var dungeonId = NormalizeDungeonId(request.DungeonId);
        var executionStrategy = dbContext.Database.CreateExecutionStrategy();
        return await executionStrategy.ExecuteAsync(async () =>
        {
            // SQL Server のリトライ戦略が有効なため、手動トランザクションは
            // ExecutionStrategy の実行スコープ内で開始する必要がある。
            dbContext.ChangeTracker.Clear();
            await using var transaction = await dbContext.Database.BeginTransactionAsync(
                IsolationLevel.Serializable);
            var now = DateTime.UtcNow;
            var entity = await dbContext.AccountDungeonRecords
                .FirstOrDefaultAsync(record =>
                    record.AccountId == request.AccountId
                    && record.DungeonId == dungeonId);

            if (entity is null)
            {
                entity = new AccountDungeonRecordEntity
                {
                    AccountDungeonRecordId = Guid.NewGuid(),
                    AccountId = request.AccountId,
                    DungeonId = dungeonId,
                    ClearCount = 1,
                    FirstClearedAt = now,
                    LastClearedAt = now,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = request.UpdatedBy,
                    UpdatedBy = request.UpdatedBy,
                    IsDeleted = false,
                };
                await dbContext.AccountDungeonRecords.AddAsync(entity);
            }
            else
            {
                entity.ClearCount += 1;
                entity.LastClearedAt = now;
                entity.UpdatedAt = now;
                entity.UpdatedBy = request.UpdatedBy;
                entity.IsDeleted = false;
            }

            await dbContext.SaveChangesAsync();
            await transaction.CommitAsync();
            return Map(entity);
        });
    }

    private static string NormalizeCategory(string? category)
    {
        return string.IsNullOrWhiteSpace(category)
            ? "ENEMY"
            : category.Trim().ToUpperInvariant();
    }

    private static string NormalizeDungeonId(string? dungeonId)
    {
        if (string.IsNullOrWhiteSpace(dungeonId))
            throw new ArgumentException("DungeonId must not be blank.", nameof(dungeonId));
        var normalized = dungeonId.Trim();
        if (normalized.Length > 100)
            throw new ArgumentException("DungeonId must be 100 characters or fewer.", nameof(dungeonId));
        return normalized;
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

    private static AccountDungeonRecordResponse Map(AccountDungeonRecordEntity entity) => new()
    {
        AccountDungeonRecordId = entity.AccountDungeonRecordId,
        AccountId = entity.AccountId,
        DungeonId = entity.DungeonId,
        ClearCount = entity.ClearCount,
        FirstClearedAt = entity.FirstClearedAt,
        LastClearedAt = entity.LastClearedAt,
        CreatedAt = entity.CreatedAt,
        UpdatedAt = entity.UpdatedAt,
        CreatedBy = entity.CreatedBy,
        UpdatedBy = entity.UpdatedBy,
    };
}
