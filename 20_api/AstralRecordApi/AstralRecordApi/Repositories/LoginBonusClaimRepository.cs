using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;
using System.Data;

namespace AstralRecordApi.Repositories;

public class LoginBonusClaimRepository(AstralRecordDbContext dbContext) : ILoginBonusClaimRepository
{
    public async Task<IReadOnlyList<LoginBonusClaimResponse>> GetByAccountIdAsync(Guid accountId, DateOnly? from, DateOnly? to)
    {
        await EnsureAccountExists(accountId);

        var query = dbContext.LoginBonusClaims
            .AsNoTracking()
            .Where(claim => claim.AccountId == accountId && !claim.IsDeleted);

        if (from is not null)
            query = query.Where(claim => claim.ClaimDate >= from.Value);
        if (to is not null)
            query = query.Where(claim => claim.ClaimDate <= to.Value);

        return await query
            .OrderBy(claim => claim.ClaimDate)
            .Select(claim => Map(claim, false))
            .ToListAsync();
    }

    public async Task<LoginBonusClaimResponse> ClaimAsync(Guid accountId, LoginBonusClaimRequest request)
    {
        if (request.ClaimDate == default)
            throw new ArgumentException("ClaimDate is required.", nameof(request));
        if (request.UpdatedBy == Guid.Empty)
            throw new ArgumentException("UpdatedBy is required.", nameof(request));

        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            await EnsureAccountExistsForUpdate(accountId);
            var existing = await FindActiveClaim(accountId, request.ClaimDate);
            if (existing is not null)
            {
                await transaction.CommitAsync();
                return Map(existing, false);
            }

            var now = DateTime.UtcNow;
            var entity = new LoginBonusClaimEntity
            {
                LoginBonusClaimId = Guid.NewGuid(),
                AccountId = accountId,
                ClaimDate = request.ClaimDate,
                ClaimedAt = now,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = request.UpdatedBy,
                UpdatedBy = request.UpdatedBy,
                IsDeleted = false,
            };
            await dbContext.LoginBonusClaims.AddAsync(entity);
            await dbContext.SaveChangesAsync();
            await transaction.CommitAsync();
            return Map(entity, true);
        });
    }

    public async Task<bool> CancelAsync(Guid accountId, DateOnly claimDate, Guid updatedBy)
    {
        if (claimDate == default)
            throw new ArgumentException("ClaimDate is required.", nameof(claimDate));
        if (updatedBy == Guid.Empty)
            throw new ArgumentException("UpdatedBy is required.", nameof(updatedBy));

        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            await EnsureAccountExistsForUpdate(accountId);
            var existing = await FindActiveClaim(accountId, claimDate);
            if (existing is null)
            {
                await transaction.CommitAsync();
                return false;
            }
            existing.IsDeleted = true;
            existing.UpdatedAt = DateTime.UtcNow;
            existing.UpdatedBy = updatedBy;
            await dbContext.SaveChangesAsync();
            await transaction.CommitAsync();
            return true;
        });
    }

    private Task<LoginBonusClaimEntity?> FindActiveClaim(Guid accountId, DateOnly claimDate)
    {
        return dbContext.LoginBonusClaims
            .FirstOrDefaultAsync(claim => claim.AccountId == accountId
                                          && claim.ClaimDate == claimDate
                                          && !claim.IsDeleted);
    }

    private async Task EnsureAccountExists(Guid accountId)
    {
        var accountExists = await dbContext.Accounts
            .AsNoTracking()
            .AnyAsync(account => account.Uuid == accountId && !account.IsDeleted);
        if (!accountExists)
            throw new KeyNotFoundException($"Account not found: {accountId}");
    }

    private async Task EnsureAccountExistsForUpdate(Guid accountId)
    {
        var exists = dbContext.Database.IsSqlServer()
            ? await dbContext.Accounts.FromSqlInterpolated($"""
                SELECT TOP (1) *
                FROM [dbo].[account] WITH (UPDLOCK, HOLDLOCK)
                WHERE [uuid] = {accountId} AND [is_deleted] = 0
                """).AsNoTracking().AnyAsync()
            : await dbContext.Accounts.AsNoTracking()
                .AnyAsync(account => account.Uuid == accountId && !account.IsDeleted);
        if (!exists)
            throw new KeyNotFoundException($"Account not found: {accountId}");
    }

    private static LoginBonusClaimResponse Map(LoginBonusClaimEntity entity, bool wasCreated) => new()
    {
        LoginBonusClaimId = entity.LoginBonusClaimId,
        AccountId = entity.AccountId,
        ClaimDate = entity.ClaimDate,
        ClaimedAt = entity.ClaimedAt,
        CreatedAt = entity.CreatedAt,
        UpdatedAt = entity.UpdatedAt,
        CreatedBy = entity.CreatedBy,
        UpdatedBy = entity.UpdatedBy,
        IsDeleted = entity.IsDeleted,
        WasCreated = wasCreated,
    };
}
