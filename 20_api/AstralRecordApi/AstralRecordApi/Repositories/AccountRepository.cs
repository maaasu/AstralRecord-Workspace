using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;
using System.Data;

namespace AstralRecordApi.Repositories;

public class AccountRepository(AstralRecordDbContext dbContext) : IAccountRepository
{
    public async Task<IReadOnlyList<AccountResponse>> GetByUserIdAsync(Guid userId)
    {
        var accounts = await dbContext.Accounts
            .AsNoTracking()
            .Include(x => x.ClassProgresses)
            .Where(x => x.UserId == userId && !x.IsDeleted)
            .OrderBy(x => x.SlotIndex)
            .ToListAsync();

        return accounts.Select(MapToResponse).ToList();
    }

    public async Task<AccountResponse?> GetByUuidAsync(Guid uuid)
    {
        var account = await dbContext.Accounts
            .AsNoTracking()
            .Include(x => x.ClassProgresses)
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
        account.ClassProgresses.Add(new AccountClassProgressEntity
        {
            AccountId = account.Uuid,
            ClassId = account.ClassId,
            Level = account.ClassLevel,
            Experience = account.ClassExperience,
            UpdatedAt = now,
            UpdatedBy = request.CreatedBy,
        });

        await dbContext.Accounts.AddAsync(account);
        await dbContext.SaveChangesAsync();

        return MapToResponse(account);
    }

    public async Task<AccountResponse?> UpdateAsync(Guid uuid, AccountUpdateRequest request)
    {
        var account = await dbContext.Accounts
            .Include(x => x.ClassProgresses)
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

        var selectedClassId = account.ClassId;
        if (request.ClassId is not null)
        {
            var classId = request.ClassId.Trim();
            if (classId.Length == 0)
                throw new ArgumentException("classId must not be blank.");
            selectedClassId = classId;
        }

        if (request.ClassLevel.HasValue != request.ClassExperience.HasValue)
            throw new ArgumentException("classLevel and classExperience must be provided together.");

        if (request.ClassProgresses is not null)
        {
            var duplicateClassId = request.ClassProgresses
                .Select(progress => progress.ClassId.Trim())
                .GroupBy(classId => classId, StringComparer.OrdinalIgnoreCase)
                .FirstOrDefault(group => group.Count() > 1);
            if (duplicateClassId is not null)
                throw new ArgumentException($"classProgresses contains duplicate classId: {duplicateClassId.Key}");

            foreach (var requestedProgress in request.ClassProgresses)
            {
                var classId = requestedProgress.ClassId.Trim();
                if (classId.Length == 0)
                    throw new ArgumentException("classProgresses.classId must not be blank.");
                UpsertClassProgress(
                    account,
                    classId,
                    requestedProgress.Level,
                    requestedProgress.Experience,
                    request.UpdatedBy
                );
            }
        }

        if (request.ClassLevel.HasValue && request.ClassExperience.HasValue)
        {
            UpsertClassProgress(
                account,
                selectedClassId,
                request.ClassLevel.Value,
                request.ClassExperience.Value,
                request.UpdatedBy
            );
        }

        var selectedProgress = FindClassProgress(account, selectedClassId);
        if (selectedProgress is null)
        {
            var level = selectedClassId.Equals(account.ClassId, StringComparison.OrdinalIgnoreCase)
                ? account.ClassLevel
                : 1;
            var experience = selectedClassId.Equals(account.ClassId, StringComparison.OrdinalIgnoreCase)
                ? account.ClassExperience
                : 0;
            selectedProgress = UpsertClassProgress(account, selectedClassId, level, experience, request.UpdatedBy);
        }
        account.ClassId = selectedProgress.ClassId;
        account.ClassLevel = selectedProgress.Level;
        account.ClassExperience = selectedProgress.Experience;

        account.UpdatedAt = DateTime.UtcNow;
        account.UpdatedBy = request.UpdatedBy;

        await dbContext.SaveChangesAsync();

        return MapToResponse(account);
    }

    /// <summary>
    /// アカウント専用の実行データを論理削除し、ユーザーの選択先を残存アカウントまたは同一スロットの新規アカウントへ切り替えます。
    /// マーケット・取引などの履歴行は保持し、削除済みアカウントを参照し続けます。
    /// </summary>
    public async Task<AccountDeleteResponse?> DeleteAsync(Guid uuid, AccountDeleteRequest request)
    {
        await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var now = DateTime.UtcNow;
        var account = await dbContext.Accounts
            .FirstOrDefaultAsync(candidate => candidate.Uuid == uuid && !candidate.IsDeleted);
        if (account is null)
            return null;

        var user = await dbContext.Users
            .FirstOrDefaultAsync(candidate => candidate.Uuid == account.UserId && !candidate.IsDeleted);
        if (user is null)
            throw new InvalidOperationException($"User {account.UserId} was not found for account {uuid}.");

        await DeleteOwnedDataAsync(account.Uuid, now, request.DeletedBy);

        account.IsDeleted = true;
        account.IsActive = false;
        account.UpdatedAt = now;
        account.UpdatedBy = request.DeletedBy;

        var remainingAccounts = await dbContext.Accounts
            .Where(candidate => candidate.UserId == account.UserId
                && candidate.Uuid != account.Uuid
                && !candidate.IsDeleted)
            .OrderBy(candidate => candidate.SlotIndex)
            .ToListAsync();

        foreach (var remaining in remainingAccounts)
            remaining.IsActive = false;

        var selected = remainingAccounts.FirstOrDefault();
        var createdReplacement = selected is null;
        if (selected is null)
        {
            selected = CreateReplacementAccount(account, now, request.DeletedBy);
            await dbContext.Accounts.AddAsync(selected);
            await dbContext.AccountClassProgresses.AddAsync(new AccountClassProgressEntity
            {
                AccountId = selected.Uuid,
                ClassId = selected.ClassId,
                Level = selected.ClassLevel,
                Experience = selected.ClassExperience,
                UpdatedAt = now,
                UpdatedBy = request.DeletedBy,
            });
        }
        selected.IsActive = true;
        user.AccountId = selected.Uuid;
        user.UpdatedAt = now;
        user.UpdatedBy = request.DeletedBy;

        await dbContext.SaveChangesAsync();
        await transaction.CommitAsync();

        return new AccountDeleteResponse
        {
            DeletedAccountId = account.Uuid,
            UserId = account.UserId,
            DeletedSlotIndex = account.SlotIndex,
            SelectedAccountId = selected.Uuid,
            CreatedReplacement = createdReplacement,
        };
    }

    private async Task DeleteOwnedDataAsync(Guid accountId, DateTime deletedAt, Guid deletedBy)
    {
        await dbContext.AccountClassProgresses
            .Where(entity => entity.AccountId == accountId)
            .ExecuteDeleteAsync();
        await dbContext.AccountLearnedSkillSigils
            .Where(entity => dbContext.AccountLearnedSkills
                .Where(learnedSkill => learnedSkill.AccountId == accountId)
                .Select(learnedSkill => learnedSkill.LearnedSkillId)
                .Contains(entity.LearnedSkillId) && !entity.IsDeleted)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(entity => entity.IsDeleted, true)
                .SetProperty(entity => entity.UpdatedAt, deletedAt)
                .SetProperty(entity => entity.UpdatedBy, deletedBy));
        await dbContext.AccountLearnedSkills
            .Where(entity => entity.AccountId == accountId && !entity.IsDeleted)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(entity => entity.IsDeleted, true)
                .SetProperty(entity => entity.UpdatedAt, deletedAt)
                .SetProperty(entity => entity.UpdatedBy, deletedBy));
        await dbContext.SkillBindPresets
            .Where(entity => entity.AccountId == accountId && !entity.IsDeleted)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(entity => entity.IsDeleted, true)
                .SetProperty(entity => entity.UpdatedAt, deletedAt)
                .SetProperty(entity => entity.UpdatedBy, deletedBy));
        await dbContext.AccountSkillTreeUnlockedNodes
            .Where(entity => dbContext.AccountSkillTreeStates
                .Where(state => state.AccountId == accountId)
                .Select(state => state.AccountSkillTreeStateId)
                .Contains(entity.AccountSkillTreeStateId))
            .ExecuteDeleteAsync();
        await dbContext.AccountSkillTreeStates
            .Where(entity => entity.AccountId == accountId && !entity.IsDeleted)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(entity => entity.IsDeleted, true)
                .SetProperty(entity => entity.UpdatedAt, deletedAt)
                .SetProperty(entity => entity.UpdatedBy, deletedBy));
        await dbContext.AccountWaystoneUnlocks
            .Where(entity => entity.AccountId == accountId && !entity.IsDeleted)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(entity => entity.IsDeleted, true)
                .SetProperty(entity => entity.UpdatedAt, deletedAt)
                .SetProperty(entity => entity.UpdatedBy, deletedBy));
        await dbContext.AccountGuideStepProgresses
            .Where(entity => entity.AccountId == accountId)
            .ExecuteDeleteAsync();
        await dbContext.AccountQuestObjectiveProgresses
            .Where(entity => dbContext.AccountQuestActives
                .Where(active => dbContext.AccountQuestStates
                    .Where(state => state.AccountId == accountId)
                    .Select(state => state.AccountQuestStateId)
                    .Contains(active.AccountQuestStateId))
                .Select(active => active.AccountQuestActiveId)
                .Contains(entity.AccountQuestActiveId))
            .ExecuteDeleteAsync();
        await dbContext.AccountQuestActives
            .Where(entity => dbContext.AccountQuestStates
                .Where(state => state.AccountId == accountId)
                .Select(state => state.AccountQuestStateId)
                .Contains(entity.AccountQuestStateId))
            .ExecuteDeleteAsync();
        await dbContext.AccountQuestCompletions
            .Where(entity => dbContext.AccountQuestStates
                .Where(state => state.AccountId == accountId)
                .Select(state => state.AccountQuestStateId)
                .Contains(entity.AccountQuestStateId))
            .ExecuteDeleteAsync();
        await dbContext.AccountQuestCooldowns
            .Where(entity => dbContext.AccountQuestStates
                .Where(state => state.AccountId == accountId)
                .Select(state => state.AccountQuestStateId)
                .Contains(entity.AccountQuestStateId))
            .ExecuteDeleteAsync();
        await dbContext.AccountQuestStates
            .Where(entity => entity.AccountId == accountId && !entity.IsDeleted)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(entity => entity.IsDeleted, true)
                .SetProperty(entity => entity.UpdatedAt, deletedAt)
                .SetProperty(entity => entity.UpdatedBy, deletedBy));
        await dbContext.LoginBonusClaims
            .Where(entity => entity.AccountId == accountId && !entity.IsDeleted)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(entity => entity.IsDeleted, true)
                .SetProperty(entity => entity.UpdatedAt, deletedAt)
                .SetProperty(entity => entity.UpdatedBy, deletedBy));
        await dbContext.InventoryEntries
            .Where(entity => dbContext.Inventories
                .Where(inventory => inventory.AccountId == accountId)
                .Select(inventory => inventory.InventoryId)
                .Contains(entity.InventoryId) && !entity.IsDeleted)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(entity => entity.IsDeleted, true)
                .SetProperty(entity => entity.UpdatedAt, deletedAt)
                .SetProperty(entity => entity.UpdatedBy, deletedBy));
        await dbContext.Inventories
            .Where(entity => entity.AccountId == accountId && !entity.IsDeleted)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(entity => entity.IsDeleted, true)
                .SetProperty(entity => entity.UpdatedAt, deletedAt)
                .SetProperty(entity => entity.UpdatedBy, deletedBy));
        await dbContext.EquipmentLoadoutSlots
            .Where(entity => dbContext.EquipmentLoadouts
                .Where(loadout => loadout.AccountId == accountId)
                .Select(loadout => loadout.EquipmentLoadoutId)
                .Contains(entity.EquipmentLoadoutId) && !entity.IsDeleted)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(entity => entity.IsDeleted, true)
                .SetProperty(entity => entity.UpdatedAt, deletedAt)
                .SetProperty(entity => entity.UpdatedBy, deletedBy));
        await dbContext.EquipmentInstanceStatRolls
            .Where(entity => dbContext.EquipmentInstances
                .Where(instance => instance.AccountId == accountId)
                .Select(instance => instance.EquipmentInstanceId)
                .Contains(entity.EquipmentInstanceId))
            .ExecuteDeleteAsync();
        await dbContext.EquipmentInstanceEnchants
            .Where(entity => dbContext.EquipmentInstances
                .Where(instance => instance.AccountId == accountId)
                .Select(instance => instance.EquipmentInstanceId)
                .Contains(entity.EquipmentInstanceId))
            .ExecuteDeleteAsync();
        await dbContext.EquipmentInstanceRunes
            .Where(entity => dbContext.EquipmentInstances
                .Where(instance => instance.AccountId == accountId)
                .Select(instance => instance.EquipmentInstanceId)
                .Contains(entity.EquipmentInstanceId))
            .ExecuteDeleteAsync();
        await dbContext.EquipmentInstances
            .Where(entity => entity.AccountId == accountId && !entity.IsDeleted)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(entity => entity.IsDeleted, true)
                .SetProperty(entity => entity.UpdatedAt, deletedAt)
                .SetProperty(entity => entity.UpdatedBy, deletedBy));
        await dbContext.EquipmentLoadouts
            .Where(entity => entity.AccountId == accountId && !entity.IsDeleted)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(entity => entity.IsDeleted, true)
                .SetProperty(entity => entity.UpdatedAt, deletedAt)
                .SetProperty(entity => entity.UpdatedBy, deletedBy));
        await dbContext.AccountMobRecords
            .Where(entity => entity.AccountId == accountId && !entity.IsDeleted)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(entity => entity.IsDeleted, true)
                .SetProperty(entity => entity.UpdatedAt, deletedAt)
                .SetProperty(entity => entity.UpdatedBy, deletedBy));
        await dbContext.AccountDungeonRecords
            .Where(entity => entity.AccountId == accountId && !entity.IsDeleted)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(entity => entity.IsDeleted, true)
                .SetProperty(entity => entity.UpdatedAt, deletedAt)
                .SetProperty(entity => entity.UpdatedBy, deletedBy));
        await dbContext.MarketAccountStates
            .Where(entity => entity.AccountId == accountId && !entity.IsDeleted)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(entity => entity.IsDeleted, true)
                .SetProperty(entity => entity.UpdatedAt, deletedAt)
                .SetProperty(entity => entity.UpdatedBy, deletedBy));
        await dbContext.MarketListings
            .Where(entity => entity.SellerAccountId == accountId && entity.Status == "ACTIVE" && !entity.IsDeleted)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(entity => entity.Status, "CANCELED")
                .SetProperty(entity => entity.StatusReason, "ACCOUNT_DELETED")
                .SetProperty(entity => entity.CanceledAt, deletedAt)
                .SetProperty(entity => entity.UpdatedAt, deletedAt)
                .SetProperty(entity => entity.UpdatedBy, deletedBy));
    }

    private static AccountEntity CreateReplacementAccount(AccountEntity deleted, DateTime now, Guid createdBy) => new()
    {
        Uuid = Guid.NewGuid(),
        UserId = deleted.UserId,
        AccountName = deleted.AccountName,
        SlotIndex = deleted.SlotIndex,
        IsActive = true,
        Mode = 0,
        Level = 1,
        TotalExperience = 0,
        ClassId = "adventurer",
        ClassLevel = 1,
        ClassExperience = 0,
        CreatedAt = now,
        UpdatedAt = now,
        CreatedBy = createdBy,
        UpdatedBy = createdBy,
        IsDeleted = false,
    };

    private static AccountClassProgressEntity? FindClassProgress(AccountEntity account, string classId) =>
        account.ClassProgresses.FirstOrDefault(progress =>
            progress.ClassId.Equals(classId, StringComparison.OrdinalIgnoreCase));

    private static AccountClassProgressEntity UpsertClassProgress(
        AccountEntity account,
        string classId,
        int level,
        long experience,
        Guid updatedBy
    )
    {
        var progress = FindClassProgress(account, classId);
        if (progress is null)
        {
            progress = new AccountClassProgressEntity
            {
                AccountId = account.Uuid,
                ClassId = classId,
            };
            account.ClassProgresses.Add(progress);
        }
        progress.Level = Math.Max(1, level);
        progress.Experience = Math.Max(0, experience);
        progress.UpdatedAt = DateTime.UtcNow;
        progress.UpdatedBy = updatedBy;
        return progress;
    }

    private static AccountResponse MapToResponse(AccountEntity account)
    {
        IReadOnlyList<AccountClassProgressResponse> progresses = account.ClassProgresses.Count == 0
            ?
            [
                new AccountClassProgressResponse
                {
                    ClassId = account.ClassId,
                    Level = account.ClassLevel,
                    Experience = account.ClassExperience,
                }
            ]
            : account.ClassProgresses
                .OrderBy(progress => progress.ClassId)
                .Select(progress => new AccountClassProgressResponse
                {
                    ClassId = progress.ClassId,
                    Level = progress.Level,
                    Experience = progress.Experience,
                })
                .ToList();

        return new AccountResponse
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
            ClassProgresses = progresses,
            CreatedAt = account.CreatedAt,
            UpdatedAt = account.UpdatedAt,
            CreatedBy = account.CreatedBy,
            UpdatedBy = account.UpdatedBy,
            IsDeleted = account.IsDeleted,
        };
    }
}
