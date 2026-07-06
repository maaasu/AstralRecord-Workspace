using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class AccountRepository(AstralRecordDbContext dbContext) : IAccountRepository
{
    public async Task<IReadOnlyList<AccountResponse>> GetByUserIdAsync(Guid userId)
    {
        var accounts = await dbContext.Accounts
            .AsNoTracking()
            .Where(x => x.UserId == userId && !x.IsDeleted)
            .OrderBy(x => x.SlotIndex)
            .ToListAsync();

        return accounts.Select(MapToResponse).ToList();
    }

    public async Task<AccountResponse?> GetByUuidAsync(Guid uuid)
    {
        var account = await dbContext.Accounts
            .AsNoTracking()
            .FirstOrDefaultAsync(x => x.Uuid == uuid && !x.IsDeleted);

        return account is null ? null : MapToResponse(account);
    }

    public async Task<AccountResponse> CreateAsync(AccountCreateRequest request)
    {
        var now = DateTime.UtcNow;
        var account = new AccountEntity
        {
            Uuid = Guid.NewGuid(),
            UserId = request.UserId,
            AccountName = request.AccountName,
            SlotIndex = request.SlotIndex,
            Mode = request.Mode,
            IsActive = true,
            Level = 1,
            TotalExperience = 0,
            ClassId = "adventurer",
            ClassLevel = 1,
            ClassExperience = 0,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = request.CreatedBy,
            UpdatedBy = request.CreatedBy,
            IsDeleted = false,
        };

        await dbContext.Accounts.AddAsync(account);
        await dbContext.SaveChangesAsync();

        return MapToResponse(account);
    }

    public async Task<AccountResponse?> UpdateAsync(Guid uuid, AccountUpdateRequest request)
    {
        var account = await dbContext.Accounts
            .FirstOrDefaultAsync(x => x.Uuid == uuid && !x.IsDeleted);

        if (account is null)
            return null;

        if (request.AccountName is not null)
            account.AccountName = request.AccountName;

        if (request.IsActive.HasValue)
            account.IsActive = request.IsActive.Value;

        if (request.Mode.HasValue)
            account.Mode = request.Mode.Value;

        if (request.MenuShortcutsJson is not null)
            account.MenuShortcutsJson = request.MenuShortcutsJson;

        if (request.Level.HasValue != request.TotalExperience.HasValue)
            throw new ArgumentException("level and totalExperience must be provided together.");

        if (request.Level.HasValue && request.TotalExperience.HasValue)
        {
            account.Level = Math.Max(1, request.Level.Value);
            account.TotalExperience = Math.Max(0, request.TotalExperience.Value);
        }

        if (request.ClassId is not null)
        {
            var classId = request.ClassId.Trim();
            if (classId.Length == 0)
                throw new ArgumentException("classId must not be blank.");
            account.ClassId = classId;
        }

        if (request.ClassLevel.HasValue != request.ClassExperience.HasValue)
            throw new ArgumentException("classLevel and classExperience must be provided together.");

        if (request.ClassLevel.HasValue && request.ClassExperience.HasValue)
        {
            account.ClassLevel = Math.Max(1, request.ClassLevel.Value);
            account.ClassExperience = Math.Max(0, request.ClassExperience.Value);
        }

        account.UpdatedAt = DateTime.UtcNow;
        account.UpdatedBy = request.UpdatedBy;

        await dbContext.SaveChangesAsync();

        return MapToResponse(account);
    }

    private static AccountResponse MapToResponse(AccountEntity account) => new()
    {
        Uuid = account.Uuid,
        UserId = account.UserId,
        AccountName = account.AccountName,
        SlotIndex = account.SlotIndex,
        IsActive = account.IsActive,
        Mode = account.Mode,
        MenuShortcutsJson = account.MenuShortcutsJson,
        Level = account.Level,
        TotalExperience = account.TotalExperience,
        ClassId = account.ClassId,
        ClassLevel = account.ClassLevel,
        ClassExperience = account.ClassExperience,
        CreatedAt = account.CreatedAt,
        UpdatedAt = account.UpdatedAt,
        CreatedBy = account.CreatedBy,
        UpdatedBy = account.UpdatedBy,
        IsDeleted = account.IsDeleted,
    };
}
