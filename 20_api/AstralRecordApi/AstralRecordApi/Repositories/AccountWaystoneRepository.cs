using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class AccountWaystoneRepository(AstralRecordDbContext dbContext) : IAccountWaystoneRepository
{
    public async Task<AccountWaystoneStateResponse> GetByAccountIdAsync(Guid accountId)
    {
        await EnsureAccountExists(accountId);
        var ids = await dbContext.AccountWaystoneUnlocks
            .AsNoTracking()
            .Where(unlock => unlock.AccountId == accountId && !unlock.IsDeleted)
            .Select(unlock => unlock.WaystoneId)
            .OrderBy(waystoneId => waystoneId)
            .ToListAsync();

        return new AccountWaystoneStateResponse
        {
            AccountId = accountId,
            UnlockedWaystoneIds = ids,
        };
    }

    public async Task<AccountWaystoneUnlockResponse> UnlockAsync(Guid accountId, AccountWaystoneUnlockRequest request)
    {
        await EnsureAccountExists(accountId);
        var waystoneId = NormalizeWaystoneId(request.WaystoneId);
        if (waystoneId.Length == 0)
            throw new ArgumentException("WaystoneId is required.", nameof(request));

        var existing = await dbContext.AccountWaystoneUnlocks
            .FirstOrDefaultAsync(unlock => unlock.AccountId == accountId
                                           && unlock.WaystoneId == waystoneId
                                           && !unlock.IsDeleted);
        if (existing is not null)
            return Map(existing);

        var now = DateTime.UtcNow;
        var entity = new AccountWaystoneUnlockEntity
        {
            AccountWaystoneUnlockId = Guid.NewGuid(),
            AccountId = accountId,
            WaystoneId = waystoneId,
            UnlockedAt = now,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = request.UpdatedBy,
            UpdatedBy = request.UpdatedBy,
            IsDeleted = false,
        };

        await dbContext.AccountWaystoneUnlocks.AddAsync(entity);
        await dbContext.SaveChangesAsync();
        return Map(entity);
    }

    private async Task EnsureAccountExists(Guid accountId)
    {
        var accountExists = await dbContext.Accounts
            .AsNoTracking()
            .AnyAsync(account => account.Uuid == accountId && !account.IsDeleted);
        if (!accountExists)
            throw new KeyNotFoundException($"Account not found: {accountId}");
    }

    private static string NormalizeWaystoneId(string waystoneId) => waystoneId.Trim();

    private static AccountWaystoneUnlockResponse Map(AccountWaystoneUnlockEntity entity) => new()
    {
        AccountWaystoneUnlockId = entity.AccountWaystoneUnlockId,
        AccountId = entity.AccountId,
        WaystoneId = entity.WaystoneId,
        UnlockedAt = entity.UnlockedAt,
        CreatedAt = entity.CreatedAt,
        UpdatedAt = entity.UpdatedAt,
        CreatedBy = entity.CreatedBy,
        UpdatedBy = entity.UpdatedBy,
        IsDeleted = entity.IsDeleted,
    };
}
