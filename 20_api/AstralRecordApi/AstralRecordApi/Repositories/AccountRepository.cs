using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;
using System.Data;

namespace AstralRecordApi.Repositories;

public class AccountRepository(AstralRecordDbContext dbContext) : IAccountRepository
{
    private const int AccountNameMaxLength = 50;

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
        var executionStrategy = dbContext.Database.CreateExecutionStrategy();
        return await executionStrategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            var created = await CreateInTransactionAsync(request);
            await transaction.CommitAsync();
            return created;
        });
    }

    private async Task<AccountResponse> CreateInTransactionAsync(AccountCreateRequest request)
    {
        var existingSlot = await dbContext.Accounts
            .Include(account => account.ClassProgresses)
            .FirstOrDefaultAsync(account => account.UserId == request.UserId
                && account.SlotIndex == request.SlotIndex
                && !account.IsDeleted);
        if (existingSlot is not null
            && existingSlot.Mode == request.Mode
            && existingSlot.CreatedBy == request.CreatedBy
            && IsRetryOfCreateRequest(existingSlot.AccountName, request.AccountName))
        {
            // Commit結果不明後の実行戦略再試行では、最初の試行で確定した行を成功結果として返す。
            return MapToResponse(existingSlot);
        }

        var now = DateTime.UtcNow;
        var hasExistingAccount = await dbContext.Accounts
            .AnyAsync(candidate => candidate.UserId == request.UserId && !candidate.IsDeleted);
        var accountName = await ResolveGeneratedAccountNameAsync(request.AccountName);
        var account = new AccountEntity
        {
            Uuid = Guid.NewGuid(),
            UserId = request.UserId,
            AccountName = accountName,
            SlotIndex = request.SlotIndex,
            Mode = request.Mode,
            IsActive = !hasExistingAccount,
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
        if (request.IsActive != true && request.AccountName is null)
            return await UpdateCoreAsync(uuid, request);

        var executionStrategy = dbContext.Database.CreateExecutionStrategy();
        return await executionStrategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            var updated = await UpdateCoreAsync(uuid, request);
            if (updated is null)
                return null;

            await transaction.CommitAsync();
            return updated;
        });
    }

    private async Task<AccountResponse?> UpdateCoreAsync(Guid uuid, AccountUpdateRequest request)
    {
        var account = await dbContext.Accounts
            .Include(x => x.ClassProgresses)
            .FirstOrDefaultAsync(x => x.Uuid == uuid && !x.IsDeleted);

        if (account is null)
            return null;

        if (request.AccountName is not null)
        {
            ValidateManualAccountName(request.AccountName);
            var duplicateExists = await dbContext.Accounts.AnyAsync(candidate =>
                candidate.Uuid != uuid
                && !candidate.IsDeleted
                && candidate.AccountName.ToLower() == request.AccountName.ToLower());
            if (duplicateExists)
                throw new AccountNameConflictException(request.AccountName);
            account.AccountName = request.AccountName;
        }

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

        var updatedAt = DateTime.UtcNow;
        if (request.IsActive == true)
        {
            var user = await dbContext.Users
                .FirstOrDefaultAsync(candidate => candidate.Uuid == account.UserId && !candidate.IsDeleted);
            if (user is null)
                throw new InvalidOperationException($"User {account.UserId} was not found for account {uuid}.");

            var otherAccounts = await dbContext.Accounts
                .Where(candidate => candidate.UserId == account.UserId
                    && candidate.Uuid != account.Uuid
                    && !candidate.IsDeleted)
                .ToListAsync();
            foreach (var otherAccount in otherAccounts)
                otherAccount.IsActive = false;

            account.IsActive = true;
            user.AccountId = account.Uuid;
            user.UpdatedAt = updatedAt;
            user.UpdatedBy = request.UpdatedBy;
        }

        account.UpdatedAt = updatedAt;
        account.UpdatedBy = request.UpdatedBy;

        await dbContext.SaveChangesAsync();

        return MapToResponse(account);
    }

    private async Task<string> ResolveGeneratedAccountNameAsync(string requestedName)
    {
        var requestedBaseName = string.IsNullOrWhiteSpace(requestedName)
            ? "Player"
            : requestedName.Trim();
        var baseName = requestedBaseName[..Math.Min(AccountNameMaxLength, requestedBaseName.Length)];
        if (!await AccountNameExistsAsync(baseName))
            return baseName;

        for (var suffixIndex = 1; suffixIndex < int.MaxValue; suffixIndex++)
        {
            var suffix = $"({suffixIndex})";
            var prefixLength = Math.Max(1, AccountNameMaxLength - suffix.Length);
            var candidate = baseName.Length + suffix.Length <= AccountNameMaxLength
                ? baseName + suffix
                : baseName[..Math.Min(prefixLength, baseName.Length)] + suffix;
            if (!await AccountNameExistsAsync(candidate))
                return candidate;
        }

        throw new InvalidOperationException("No generated account name is available.");
    }

    private static bool IsRetryOfCreateRequest(string existingName, string requestedName)
    {
        var requestedBaseName = string.IsNullOrWhiteSpace(requestedName)
            ? "Player"
            : requestedName.Trim();
        var baseName = requestedBaseName[..Math.Min(AccountNameMaxLength, requestedBaseName.Length)];
        if (string.Equals(existingName, baseName, StringComparison.Ordinal))
            return true;
        if (!existingName.StartsWith(baseName + "(", StringComparison.Ordinal)
            || !existingName.EndsWith(")", StringComparison.Ordinal))
            return false;

        var suffix = existingName[(baseName.Length + 1)..^1];
        return int.TryParse(suffix, out var suffixIndex) && suffixIndex > 0;
    }

    private Task<bool> AccountNameExistsAsync(string accountName)
    {
        var normalizedName = accountName.ToLower();
        return dbContext.Accounts.AnyAsync(candidate =>
            !candidate.IsDeleted && candidate.AccountName.ToLower() == normalizedName);
    }

    private static void ValidateManualAccountName(string accountName)
    {
        if (accountName.Length is < 1 or > AccountNameMaxLength
            || accountName.Any(character =>
                !((character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z'))))
            throw new ArgumentException("Account name must contain only ASCII letters and be 1-50 characters long.");
    }

    /// <summary>
    /// アカウント専用の実行データを論理削除し、ユーザーの選択先を残存アカウントまたは同一スロットの新規アカウントへ切り替えます。
    /// マーケット・取引などの履歴行は保持し、削除済みアカウントを参照し続けます。
    /// </summary>
    public async Task<AccountDeleteResponse?> DeleteAsync(Guid uuid, AccountDeleteRequest request)
    {
        var executionStrategy = dbContext.Database.CreateExecutionStrategy();
        return await executionStrategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            var result = await DeleteInTransactionAsync(uuid, request);
            if (result is null)
                return null;

            await transaction.CommitAsync();
            return result;
        });
    }

    private async Task<AccountDeleteResponse?> DeleteInTransactionAsync(Guid uuid, AccountDeleteRequest request)
    {
        var committedReceipt = await dbContext.AccountDeleteReceipts
            .AsNoTracking()
            .FirstOrDefaultAsync(receipt => receipt.DeletedAccountId == uuid);
        if (committedReceipt is not null)
        {
            return committedReceipt.DeletedBy == request.DeletedBy
                ? MapToDeleteResponse(committedReceipt)
                : null;
        }

        var now = DateTime.UtcNow;
        var account = await dbContext.Accounts
            .FirstOrDefaultAsync(candidate => candidate.Uuid == uuid);
        if (account is null)
            return null;
        if (account.IsDeleted)
        {
            // Commit結果不明後の実行戦略再試行では、最初の試行で論理削除済みになった結果を返す。
            return account.UpdatedBy == request.DeletedBy
                ? await RebuildCommittedDeleteResponseAsync(account)
                : null;
        }

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

        dbContext.AccountDeleteReceipts.Add(new AccountDeleteReceiptEntity
        {
            DeletedAccountId = account.Uuid,
            UserId = account.UserId,
            DeletedSlotIndex = account.SlotIndex,
            SelectedAccountId = selected.Uuid,
            CreatedReplacement = createdReplacement,
            DeletedBy = request.DeletedBy,
            CompletedAt = now,
        });

        await dbContext.SaveChangesAsync();

        return new AccountDeleteResponse
        {
            DeletedAccountId = account.Uuid,
            UserId = account.UserId,
            DeletedSlotIndex = account.SlotIndex,
            SelectedAccountId = selected.Uuid,
            CreatedReplacement = createdReplacement,
        };
    }

    private async Task<AccountDeleteResponse?> RebuildCommittedDeleteResponseAsync(AccountEntity deleted)
    {
        var selected = await dbContext.Accounts
            .Where(candidate => candidate.UserId == deleted.UserId
                && !candidate.IsDeleted)
            .OrderBy(candidate => candidate.SlotIndex)
            .FirstOrDefaultAsync();
        if (selected is null)
            return null;

        return new AccountDeleteResponse
        {
            DeletedAccountId = deleted.Uuid,
            UserId = deleted.UserId,
            DeletedSlotIndex = deleted.SlotIndex,
            SelectedAccountId = selected.Uuid,
            CreatedReplacement = selected.SlotIndex == deleted.SlotIndex,
        };
    }

    private static AccountDeleteResponse MapToDeleteResponse(AccountDeleteReceiptEntity receipt) => new()
    {
        DeletedAccountId = receipt.DeletedAccountId,
        UserId = receipt.UserId,
        DeletedSlotIndex = receipt.DeletedSlotIndex,
        SelectedAccountId = receipt.SelectedAccountId,
        CreatedReplacement = receipt.CreatedReplacement,
    };

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
