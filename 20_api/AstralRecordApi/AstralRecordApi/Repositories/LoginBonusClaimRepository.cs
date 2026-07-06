using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

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
        await EnsureAccountExists(accountId);
        if (request.ClaimDate == default)
            throw new ArgumentException("ClaimDate is required.", nameof(request));
        if (request.UpdatedBy == Guid.Empty)
            throw new ArgumentException("UpdatedBy is required.", nameof(request));

        var existing = await FindActiveClaim(accountId, request.ClaimDate);
        if (existing is not null)
            return Map(existing, false);

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
        try
        {
            await dbContext.SaveChangesAsync();
        }
        catch (DbUpdateException)
        {
            dbContext.Entry(entity).State = EntityState.Detached;
            var raced = await FindActiveClaim(accountId, request.ClaimDate);
            if (raced is not null)
                return Map(raced, false);
            throw;
        }

        return Map(entity, true);
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
