using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;
using System.Data;
using System.Text.Json;

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

    public async Task<AccountResponse?> ResolveAsync(string? selector, string? userMcid)
    {
        var normalizedSelector = selector?.Trim();
        var normalizedMcid = userMcid?.Trim();
        if (string.IsNullOrEmpty(normalizedSelector))
        {
            if (string.IsNullOrEmpty(normalizedMcid))
                throw new ArgumentException("selector or user_mcid is required.");

            var selectedUsers = await dbContext.Users
                .AsNoTracking()
                .Where(user => !user.IsDeleted && user.Mcid.ToLower() == normalizedMcid.ToLower())
                .Select(user => user.AccountId)
                .Take(2)
                .ToListAsync();
            if (selectedUsers.Count != 1 || selectedUsers[0] is null)
                return null;
            return await GetByUuidAsync(selectedUsers[0]!.Value);
        }

        var scopedUserId = Guid.Empty;
        if (!string.IsNullOrEmpty(normalizedMcid))
        {
            var users = await dbContext.Users
                .AsNoTracking()
                .Where(user => !user.IsDeleted && user.Mcid.ToLower() == normalizedMcid.ToLower())
                .Select(user => user.Uuid)
                .Take(2)
                .ToListAsync();
            if (users.Count != 1)
                return null;
            scopedUserId = users[0];
        }

        AccountEntity? account;
        if (Guid.TryParse(normalizedSelector, out var accountId))
        {
            account = await dbContext.Accounts
                .AsNoTracking()
                .Include(candidate => candidate.ClassProgresses)
                .SingleOrDefaultAsync(candidate => candidate.Uuid == accountId && !candidate.IsDeleted);
            if (account is not null && scopedUserId != Guid.Empty && account.UserId != scopedUserId)
                return null;
        }
        else if (normalizedSelector.Length is 1 or 2 && normalizedSelector.All(char.IsAsciiDigit))
        {
            if (scopedUserId == Guid.Empty)
                throw new ArgumentException("user_mcid is required when selector is a slot number.");
            var slotIndex = int.Parse(normalizedSelector, System.Globalization.CultureInfo.InvariantCulture);
            account = await dbContext.Accounts
                .AsNoTracking()
                .Include(candidate => candidate.ClassProgresses)
                .SingleOrDefaultAsync(candidate => candidate.UserId == scopedUserId
                    && candidate.SlotIndex == slotIndex && !candidate.IsDeleted);
        }
        else
        {
            if (normalizedSelector.Length < 3)
                throw new ArgumentException("account name selector must be at least 3 characters long.");
            account = await dbContext.Accounts
                .AsNoTracking()
                .Include(candidate => candidate.ClassProgresses)
                .SingleOrDefaultAsync(candidate => !candidate.IsDeleted
                    && candidate.AccountName.ToLower() == normalizedSelector.ToLower());
            if (account is not null && scopedUserId != Guid.Empty && account.UserId != scopedUserId)
                return null;
        }

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
        if (request.SlotIndex is < 0 or > 99)
            throw new ArgumentException("slotIndex must be between 0 and 99.");

        var requestedSlot = request.SlotIndex ?? await FindLowestAvailableSlotAsync(request.UserId);
        if (requestedSlot < 0)
            throw new ArgumentException("No account slot is available between 0 and 99.");

        var existingSlot = await dbContext.Accounts
            .Include(account => account.ClassProgresses)
            .FirstOrDefaultAsync(account => account.UserId == request.UserId
                && account.SlotIndex == requestedSlot
                && !account.IsDeleted);
        if (existingSlot is not null
            && existingSlot.Mode == request.Mode
            && existingSlot.CreatedBy == request.CreatedBy
            && IsRetryOfCreateRequest(existingSlot.AccountName, request.AccountName))
        {
            // Commit結果不明後の実行戦略再試行では、最初の試行で確定した行を成功結果として返す。
            return MapToResponse(existingSlot);
        }
        if (existingSlot is not null)
            throw new AccountCloneConflictException("TARGET_ACCOUNT_EXISTS", "The target account slot already exists.", existingSlot.Uuid);

        var now = DateTime.UtcNow;
        var hasExistingAccount = await dbContext.Accounts
            .AnyAsync(candidate => candidate.UserId == request.UserId && !candidate.IsDeleted);
        var accountName = await ResolveGeneratedAccountNameAsync(request.AccountName);
        var account = new AccountEntity
        {
            Uuid = Guid.NewGuid(),
            UserId = request.UserId,
            AccountName = accountName,
            SlotIndex = requestedSlot,
            Mode = request.Mode,
            IsActive = !hasExistingAccount,
            Level = 1,
            TotalExperience = 0,
            ClassId = "adventurer",
            ClassLevel = 1,
            ClassExperience = 0,
            ProgressVersion = 1,
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

    public async Task<AccountCloneResponse?> CloneAsync(Guid sourceUuid, AccountCloneRequest request)
    {
        if (request.TargetSlotIndex is < 0 or > 99)
            throw new ArgumentException("targetSlotIndex must be between 0 and 99.");

        var cloneUuid = Guid.NewGuid();
        Guid? replacedAccountId = null;
        var executionStrategy = dbContext.Database.CreateExecutionStrategy();
        return await executionStrategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            var cloned = await CloneInTransactionAsync(sourceUuid, request, cloneUuid, replacedAccountId);
            if (cloned is null)
                return null;

            replacedAccountId = cloned.ReplacedAccountId ?? replacedAccountId;
            await transaction.CommitAsync();
            return cloned;
        });
    }

    private async Task<AccountCloneResponse?> CloneInTransactionAsync(
        Guid sourceUuid,
        AccountCloneRequest request,
        Guid cloneUuid,
        Guid? replacedAccountId)
    {
        var committedClone = await dbContext.Accounts
            .AsNoTracking()
            .Include(account => account.ClassProgresses)
            .SingleOrDefaultAsync(account => account.Uuid == cloneUuid && !account.IsDeleted);
        if (committedClone is not null)
        {
            return new AccountCloneResponse
            {
                Account = MapToResponse(committedClone),
                ReplacedAccountId = replacedAccountId,
            };
        }

        var targetUser = await FindUserForUpdateAsync(request.TargetUserId);
        if (targetUser is null)
            return null;

        var lockedAccounts = await FindAccountsForCloneAsync(sourceUuid, request.TargetUserId, request.TargetSlotIndex);
        var source = lockedAccounts.SingleOrDefault(account => account.Uuid == sourceUuid && !account.IsDeleted);
        if (source is null)
            return null;

        var target = lockedAccounts.SingleOrDefault(account => account.UserId == request.TargetUserId
            && account.SlotIndex == request.TargetSlotIndex && !account.IsDeleted);
        if (target?.Uuid == source.Uuid)
            throw new ArgumentException("The source account cannot be overwritten in place.");

        if (await HasActiveSkillTreeSessionAsync(source.Uuid))
            throw new AccountCloneConflictException("SOURCE_ACCOUNT_SESSION_ACTIVE", "The source account has an active runtime session.");
        if (target is not null && await HasActiveSkillTreeSessionAsync(target.Uuid))
            throw new AccountCloneConflictException("TARGET_ACCOUNT_SESSION_ACTIVE", "The target account has an active runtime session.", target.Uuid);

        if (target is not null && !request.Overwrite)
            throw new AccountCloneConflictException("TARGET_ACCOUNT_EXISTS", "The target account slot already exists.", target.Uuid);
        if (target is not null && request.ExpectedTargetAccountId != target.Uuid)
            throw new AccountCloneConflictException("TARGET_CHANGED", "The target account changed after confirmation.", target.Uuid);
        if (target is null && request.ExpectedTargetAccountId.HasValue)
            throw new AccountCloneConflictException("TARGET_CHANGED", "The target account no longer exists.");

        var now = DateTime.UtcNow;
        var targetWasActive = target?.IsActive == true;
        if (target is not null)
        {
            await DeleteOwnedDataAsync(target.Uuid, now, request.CreatedBy);
            target.IsDeleted = true;
            target.IsActive = false;
            target.UpdatedAt = now;
            target.UpdatedBy = request.CreatedBy;
        }

        var targetAccountId = target?.Uuid ?? Guid.Empty;
        var hasOtherActiveAccount = await dbContext.Accounts.AnyAsync(account => account.UserId == request.TargetUserId
            && !account.IsDeleted && account.Uuid != targetAccountId);
        var clonedAccount = new AccountEntity
        {
            Uuid = cloneUuid,
            UserId = request.TargetUserId,
            AccountName = await ResolveGeneratedAccountNameAsync(source.AccountName),
            SlotIndex = request.TargetSlotIndex,
            IsActive = targetWasActive || (!hasOtherActiveAccount && target is null),
            // mode はユーザー権限に関わるため、複製元の管理者 mode を複製しない。
            Mode = 0,
            MenuShortcutsJson = source.MenuShortcutsJson,
            Level = source.Level,
            TotalExperience = source.TotalExperience,
            HighestLevel = source.HighestLevel,
            RebirthOriginalLevel = source.RebirthOriginalLevel,
            RebirthExperienceRemainder = source.RebirthExperienceRemainder,
            ClassId = source.ClassId,
            ClassLevel = source.ClassLevel,
            ClassExperience = source.ClassExperience,
            ProgressVersion = Math.Max(1, source.ProgressVersion),
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = request.CreatedBy,
            UpdatedBy = request.CreatedBy,
            IsDeleted = false,
        };

        await dbContext.Accounts.AddAsync(clonedAccount);
        await CloneOwnedPlayerDataAsync(source, clonedAccount, now, request.CreatedBy);

        if (clonedAccount.IsActive)
        {
            targetUser.AccountId = clonedAccount.Uuid;
            targetUser.UpdatedAt = now;
            targetUser.UpdatedBy = request.CreatedBy;
        }

        await dbContext.SaveChangesAsync();
        return new AccountCloneResponse
        {
            Account = MapToResponse(clonedAccount),
            ReplacedAccountId = target?.Uuid ?? replacedAccountId,
        };
    }

    private async Task<UserEntity?> FindUserForUpdateAsync(Guid userId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.Users.FromSqlInterpolated($"""
                SELECT * FROM [dbo].[user] WITH (UPDLOCK, HOLDLOCK)
                WHERE [uuid] = {userId} AND [is_deleted] = 0
                """).SingleOrDefaultAsync();
        }
        return await dbContext.Users.SingleOrDefaultAsync(user => user.Uuid == userId && !user.IsDeleted);
    }

    private async Task<List<AccountEntity>> FindAccountsForCloneAsync(Guid sourceUuid, Guid targetUserId, int targetSlotIndex)
    {
        var accounts = dbContext.Database.IsSqlServer()
            ? dbContext.Accounts.FromSqlInterpolated($"""
                SELECT * FROM [dbo].[account] WITH (UPDLOCK, HOLDLOCK)
                WHERE [uuid] = {sourceUuid}
                   OR ([user_id] = {targetUserId} AND [slot_index] = {targetSlotIndex} AND [is_deleted] = 0)
                ORDER BY [uuid]
                OFFSET 0 ROWS
                """)
            : dbContext.Accounts.Where(account => account.Uuid == sourceUuid
                || (account.UserId == targetUserId && account.SlotIndex == targetSlotIndex && !account.IsDeleted));
        return await accounts.Include(account => account.ClassProgresses).ToListAsync();
    }

    private Task<bool> HasActiveSkillTreeSessionAsync(Guid accountId) =>
        SkillTreeSessionReads.Query(dbContext).AnyAsync(session => session.AccountId == accountId
            && !session.Closed && session.ExpiresAtUtc > DateTime.UtcNow);

    private async Task CloneOwnedPlayerDataAsync(AccountEntity source, AccountEntity target, DateTime now, Guid actorId)
    {
        var sourceAccountId = source.Uuid;
        var targetAccountId = target.Uuid;

        var classProgresses = source.ClassProgresses.Select(progress => new AccountClassProgressEntity
        {
            AccountId = targetAccountId, ClassId = progress.ClassId, Level = progress.Level,
            Experience = progress.Experience, UpdatedAt = now, UpdatedBy = actorId,
        }).ToList();
        if (classProgresses.Count == 0)
        {
            classProgresses.Add(new AccountClassProgressEntity
            {
                AccountId = targetAccountId, ClassId = target.ClassId, Level = target.ClassLevel,
                Experience = target.ClassExperience, UpdatedAt = now, UpdatedBy = actorId,
            });
        }
        target.ClassProgresses = classProgresses;

        var learnedSkills = await dbContext.AccountLearnedSkills.AsNoTracking()
            .Include(skill => skill.Sigils)
            .Where(skill => skill.AccountId == sourceAccountId && !skill.IsDeleted)
            .ToListAsync();
        var learnedSkillIds = learnedSkills.ToDictionary(skill => skill.LearnedSkillId, _ => Guid.NewGuid());
        await dbContext.AccountLearnedSkills.AddRangeAsync(learnedSkills.Select(skill => new AccountLearnedSkillEntity
        {
            LearnedSkillId = learnedSkillIds[skill.LearnedSkillId], AccountId = targetAccountId,
            SkillId = skill.SkillId, Level = skill.Level, Version = skill.Version,
            CreatedAt = now, UpdatedAt = now, CreatedBy = actorId, UpdatedBy = actorId, IsDeleted = false,
        }));
        await dbContext.AccountLearnedSkillSigils.AddRangeAsync(learnedSkills
            .SelectMany(skill => skill.Sigils.Where(sigil => !sigil.IsDeleted), (skill, sigil) => new AccountLearnedSkillSigilEntity
            {
                LearnedSkillSigilId = Guid.NewGuid(), LearnedSkillId = learnedSkillIds[skill.LearnedSkillId],
                SigilId = sigil.SigilId, EquipGroupId = sigil.EquipGroupId, SlotIndex = sigil.SlotIndex,
                CreatedAt = now, UpdatedAt = now, CreatedBy = actorId, UpdatedBy = actorId, IsDeleted = false,
            }));

        var inventories = await dbContext.Inventories.AsNoTracking()
            .Where(inventory => inventory.AccountId == sourceAccountId && !inventory.IsDeleted).ToListAsync();
        var inventoryIds = inventories.ToDictionary(inventory => inventory.InventoryId, _ => Guid.NewGuid());
        await dbContext.Inventories.AddRangeAsync(inventories.Select(inventory => new InventoryEntity
        {
            InventoryId = inventoryIds[inventory.InventoryId], AccountId = targetAccountId,
            InventoryType = inventory.InventoryType, InventoryProfile = inventory.InventoryProfile,
            SlotCapacity = inventory.SlotCapacity, IsEnabled = inventory.IsEnabled, MetadataJson = inventory.MetadataJson,
            CreatedAt = now, UpdatedAt = now, CreatedBy = actorId, UpdatedBy = actorId, IsDeleted = false,
        }));

        var equipment = await dbContext.EquipmentInstances.AsNoTracking()
            .Where(instance => instance.AccountId == sourceAccountId && !instance.IsDeleted).ToListAsync();
        var equipmentIds = equipment.ToDictionary(instance => instance.EquipmentInstanceId, _ => Guid.NewGuid());
        await dbContext.EquipmentInstances.AddRangeAsync(equipment.Select(instance => new EquipmentInstanceEntity
        {
            EquipmentInstanceId = equipmentIds[instance.EquipmentInstanceId], AccountId = targetAccountId,
            ItemId = instance.ItemId, EnhanceLevel = instance.EnhanceLevel, RuneMaxSlots = instance.RuneMaxSlots,
            TranscendenceRank = instance.TranscendenceRank, DurabilityMax = instance.DurabilityMax,
            DurabilityValue = instance.DurabilityValue, CreatedAt = now, UpdatedAt = now,
            CreatedBy = actorId, UpdatedBy = actorId, IsDeleted = false,
        }));
        if (equipmentIds.Count > 0)
        {
            var sourceEquipmentIds = equipmentIds.Keys.ToList();
            var statRolls = await dbContext.EquipmentInstanceStatRolls.AsNoTracking()
                .Where(stat => sourceEquipmentIds.Contains(stat.EquipmentInstanceId)).ToListAsync();
            var enchants = await dbContext.EquipmentInstanceEnchants.AsNoTracking()
                .Where(enchant => sourceEquipmentIds.Contains(enchant.EquipmentInstanceId)).ToListAsync();
            var runes = await dbContext.EquipmentInstanceRunes.AsNoTracking()
                .Where(rune => sourceEquipmentIds.Contains(rune.EquipmentInstanceId)).ToListAsync();
            await dbContext.EquipmentInstanceStatRolls.AddRangeAsync(statRolls.Select(stat => new EquipmentInstanceStatRollEntity
            {
                StatRollId = Guid.NewGuid(), EquipmentInstanceId = equipmentIds[stat.EquipmentInstanceId],
                Status = stat.Status, RandomMin = stat.RandomMin, RandomMax = stat.RandomMax, SortOrder = stat.SortOrder,
                CreatedAt = now, UpdatedAt = now, CreatedBy = actorId, UpdatedBy = actorId,
            }));
            await dbContext.EquipmentInstanceEnchants.AddRangeAsync(enchants.Select(enchant => new EquipmentInstanceEnchantEntity
            {
                EnchantId = Guid.NewGuid(), EquipmentInstanceId = equipmentIds[enchant.EquipmentInstanceId],
                SlotIndex = enchant.SlotIndex, EnchantMasterId = enchant.EnchantMasterId, EffectId = enchant.EffectId,
                Status = enchant.Status, Type = enchant.Type, Value = enchant.Value,
                CreatedAt = now, UpdatedAt = now, CreatedBy = actorId, UpdatedBy = actorId,
            }));
            await dbContext.EquipmentInstanceRunes.AddRangeAsync(runes.Select(rune => new EquipmentInstanceRuneEntity
            {
                RuneId = Guid.NewGuid(), EquipmentInstanceId = equipmentIds[rune.EquipmentInstanceId],
                SlotIndex = rune.SlotIndex, ItemId = rune.ItemId,
                CreatedAt = now, UpdatedAt = now, CreatedBy = actorId, UpdatedBy = actorId,
            }));
        }

        if (inventoryIds.Count > 0)
        {
            var sourceInventoryIds = inventoryIds.Keys.ToList();
            var entries = await dbContext.InventoryEntries.AsNoTracking()
                .Where(entry => sourceInventoryIds.Contains(entry.InventoryId) && !entry.IsDeleted
                    && entry.ItemId != DonationRules.PaidAstraldItemId).ToListAsync();
            await dbContext.InventoryEntries.AddRangeAsync(entries.Select(entry => new InventoryEntryEntity
            {
                InventoryEntryId = Guid.NewGuid(), InventoryId = inventoryIds[entry.InventoryId],
                SlotIndex = entry.SlotIndex, ItemCategory = entry.ItemCategory, ItemId = entry.ItemId,
                InstanceType = entry.InstanceType,
                InstanceId = entry.InstanceId is Guid sourceInstanceId && equipmentIds.TryGetValue(sourceInstanceId, out var targetInstanceId)
                    ? targetInstanceId : entry.InstanceId,
                Quantity = entry.Quantity, MetadataJson = entry.MetadataJson,
                CreatedAt = now, UpdatedAt = now, CreatedBy = actorId, UpdatedBy = actorId, IsDeleted = false,
            }));
        }

        var loadouts = await dbContext.EquipmentLoadouts.AsNoTracking()
            .Where(loadout => loadout.AccountId == sourceAccountId && !loadout.IsDeleted).ToListAsync();
        var loadoutIds = loadouts.ToDictionary(loadout => loadout.EquipmentLoadoutId, _ => Guid.NewGuid());
        await dbContext.EquipmentLoadouts.AddRangeAsync(loadouts.Select(loadout => new EquipmentLoadoutEntity
        {
            EquipmentLoadoutId = loadoutIds[loadout.EquipmentLoadoutId], AccountId = targetAccountId,
            LoadoutProfile = loadout.LoadoutProfile, LoadoutName = loadout.LoadoutName, SortOrder = loadout.SortOrder,
            IsActive = loadout.IsActive, MetadataJson = loadout.MetadataJson,
            CreatedAt = now, UpdatedAt = now, CreatedBy = actorId, UpdatedBy = actorId, IsDeleted = false,
        }));
        if (loadoutIds.Count > 0)
        {
            var sourceLoadoutIds = loadoutIds.Keys.ToList();
            var loadoutSlots = await dbContext.EquipmentLoadoutSlots.AsNoTracking()
                .Where(slot => sourceLoadoutIds.Contains(slot.EquipmentLoadoutId) && !slot.IsDeleted).ToListAsync();
            await dbContext.EquipmentLoadoutSlots.AddRangeAsync(loadoutSlots
                .Where(slot => equipmentIds.ContainsKey(slot.EquipmentInstanceId))
                .Select(slot => new EquipmentLoadoutSlotEntity
                {
                    EquipmentLoadoutSlotId = Guid.NewGuid(), EquipmentLoadoutId = loadoutIds[slot.EquipmentLoadoutId],
                    SlotType = slot.SlotType, SlotIndex = slot.SlotIndex, EquipmentInstanceId = equipmentIds[slot.EquipmentInstanceId],
                    CreatedAt = now, UpdatedAt = now, CreatedBy = actorId, UpdatedBy = actorId, IsDeleted = false,
                }));
        }

        var skillTreeStates = await dbContext.AccountSkillTreeStates.AsNoTracking()
            .Include(state => state.UnlockedNodes)
            .Where(state => state.AccountId == sourceAccountId && !state.IsDeleted).ToListAsync();
        var skillTreeStateIds = skillTreeStates.ToDictionary(state => state.AccountSkillTreeStateId, _ => Guid.NewGuid());
        await dbContext.AccountSkillTreeStates.AddRangeAsync(skillTreeStates.Select(state => new AccountSkillTreeStateEntity
        {
            AccountSkillTreeStateId = skillTreeStateIds[state.AccountSkillTreeStateId], AccountId = targetAccountId,
            Version = state.Version, DefinitionGenerationId = state.DefinitionGenerationId,
            CreatedAt = now, UpdatedAt = now, CreatedBy = actorId, UpdatedBy = actorId, IsDeleted = false,
        }));
        await dbContext.AccountSkillTreeUnlockedNodes.AddRangeAsync(skillTreeStates.SelectMany(state => state.UnlockedNodes,
            (state, node) => new AccountSkillTreeUnlockedNodeEntity
            {
                AccountSkillTreeUnlockedNodeId = Guid.NewGuid(),
                AccountSkillTreeStateId = skillTreeStateIds[state.AccountSkillTreeStateId], NodeId = node.NodeId,
                ConsumedClassId = node.ConsumedClassId, CreatedAt = now, UpdatedAt = now,
                CreatedBy = actorId, UpdatedBy = actorId,
            }));

        var bindPresets = await dbContext.SkillBindPresets.AsNoTracking()
            .Where(preset => preset.AccountId == sourceAccountId && !preset.IsDeleted).ToListAsync();
        await dbContext.SkillBindPresets.AddRangeAsync(bindPresets.Select(preset => new SkillBindPresetEntity
        {
            SkillBindPresetId = Guid.NewGuid(), AccountId = targetAccountId, PresetIndex = preset.PresetIndex,
            ActiveSkillSlotsJson = RemapLearnedSkillBindings(preset.ActiveSkillSlotsJson, learnedSkillIds),
            LeftClickSkillId = RemapLearnedSkillBinding(preset.LeftClickSkillId, learnedSkillIds),
            PassiveSkillSlotsJson = RemapLearnedSkillBindings(preset.PassiveSkillSlotsJson, learnedSkillIds),
            IsUnlocked = preset.IsUnlocked,
            IsSelected = preset.IsSelected, Version = preset.Version,
            CreatedAt = now, UpdatedAt = now, CreatedBy = actorId, UpdatedBy = actorId, IsDeleted = false,
        }));

        var waystones = await dbContext.AccountWaystoneUnlocks.AsNoTracking()
            .Where(unlock => unlock.AccountId == sourceAccountId && !unlock.IsDeleted).ToListAsync();
        await dbContext.AccountWaystoneUnlocks.AddRangeAsync(waystones.Select(unlock => new AccountWaystoneUnlockEntity
        {
            AccountWaystoneUnlockId = Guid.NewGuid(), AccountId = targetAccountId, WaystoneId = unlock.WaystoneId,
            UnlockedAt = unlock.UnlockedAt, CreatedAt = now, UpdatedAt = now,
            CreatedBy = actorId, UpdatedBy = actorId, IsDeleted = false,
        }));

        var guideSteps = await dbContext.AccountGuideStepProgresses.AsNoTracking()
            .Where(progress => progress.AccountId == sourceAccountId).ToListAsync();
        await dbContext.AccountGuideStepProgresses.AddRangeAsync(guideSteps.Select(progress => new AccountGuideStepProgressEntity
        {
            AccountGuideStepProgressId = Guid.NewGuid(), AccountId = targetAccountId,
            GuideId = progress.GuideId, StepId = progress.StepId, CompletedAt = progress.CompletedAt,
            CreatedAt = now, CreatedBy = actorId,
        }));

        var questStates = await dbContext.AccountQuestStates.AsNoTracking()
            .Include(state => state.ActiveQuests).ThenInclude(active => active.ObjectiveProgress)
            .Include(state => state.Completions).Include(state => state.Cooldowns)
            .Where(state => state.AccountId == sourceAccountId && !state.IsDeleted).ToListAsync();
        var questStateIds = questStates.ToDictionary(state => state.AccountQuestStateId, _ => Guid.NewGuid());
        var activeQuestIds = questStates.SelectMany(state => state.ActiveQuests)
            .ToDictionary(active => active.AccountQuestActiveId, _ => Guid.NewGuid());
        await dbContext.AccountQuestStates.AddRangeAsync(questStates.Select(state => new AccountQuestStateEntity
        {
            AccountQuestStateId = questStateIds[state.AccountQuestStateId], AccountId = targetAccountId,
            Version = state.Version, CreatedAt = now, UpdatedAt = now, CreatedBy = actorId, UpdatedBy = actorId,
            IsDeleted = false,
        }));
        await dbContext.AccountQuestActives.AddRangeAsync(questStates.SelectMany(state => state.ActiveQuests,
            (state, active) => new AccountQuestActiveEntity
            {
                AccountQuestActiveId = activeQuestIds[active.AccountQuestActiveId],
                AccountQuestStateId = questStateIds[state.AccountQuestStateId], QuestId = active.QuestId,
                AcceptedAt = active.AcceptedAt, AcceptedNpcId = active.AcceptedNpcId, ReadyToTurnIn = active.ReadyToTurnIn,
                CreatedAt = now, UpdatedAt = now, CreatedBy = actorId, UpdatedBy = actorId,
            }));
        await dbContext.AccountQuestObjectiveProgresses.AddRangeAsync(questStates.SelectMany(state => state.ActiveQuests)
            .SelectMany(active => active.ObjectiveProgress, (active, objective) => new AccountQuestObjectiveProgressEntity
            {
                AccountQuestObjectiveProgressId = Guid.NewGuid(), AccountQuestActiveId = activeQuestIds[active.AccountQuestActiveId],
                ObjectiveId = objective.ObjectiveId, Progress = objective.Progress,
                CreatedAt = now, UpdatedAt = now, CreatedBy = actorId, UpdatedBy = actorId,
            }));
        await dbContext.AccountQuestCompletions.AddRangeAsync(questStates.SelectMany(state => state.Completions,
            (state, completion) => new AccountQuestCompletionEntity
            {
                AccountQuestCompletionId = Guid.NewGuid(), AccountQuestStateId = questStateIds[state.AccountQuestStateId],
                QuestId = completion.QuestId, CompletedAt = completion.CompletedAt,
                CreatedAt = now, UpdatedAt = now, CreatedBy = actorId, UpdatedBy = actorId,
            }));
        await dbContext.AccountQuestCooldowns.AddRangeAsync(questStates.SelectMany(state => state.Cooldowns,
            (state, cooldown) => new AccountQuestCooldownEntity
            {
                AccountQuestCooldownId = Guid.NewGuid(), AccountQuestStateId = questStateIds[state.AccountQuestStateId],
                QuestId = cooldown.QuestId, CooldownUntil = cooldown.CooldownUntil,
                CreatedAt = now, UpdatedAt = now, CreatedBy = actorId, UpdatedBy = actorId,
            }));

        var mobRecords = await dbContext.AccountMobRecords.AsNoTracking()
            .Where(record => record.AccountId == sourceAccountId && !record.IsDeleted).ToListAsync();
        await dbContext.AccountMobRecords.AddRangeAsync(mobRecords.Select(record => new AccountMobRecordEntity
        {
            AccountMobRecordId = Guid.NewGuid(), AccountId = targetAccountId, MobId = record.MobId,
            MobCategory = record.MobCategory, DefeatCount = record.DefeatCount,
            FirstDefeatedAt = record.FirstDefeatedAt, LastDefeatedAt = record.LastDefeatedAt,
            CreatedAt = now, UpdatedAt = now, CreatedBy = actorId, UpdatedBy = actorId, IsDeleted = false,
        }));
        var dungeonRecords = await dbContext.AccountDungeonRecords.AsNoTracking()
            .Where(record => record.AccountId == sourceAccountId && !record.IsDeleted).ToListAsync();
        await dbContext.AccountDungeonRecords.AddRangeAsync(dungeonRecords.Select(record => new AccountDungeonRecordEntity
        {
            AccountDungeonRecordId = Guid.NewGuid(), AccountId = targetAccountId, DungeonId = record.DungeonId,
            ClearCount = record.ClearCount, FirstClearedAt = record.FirstClearedAt, LastClearedAt = record.LastClearedAt,
            CreatedAt = now, UpdatedAt = now, CreatedBy = actorId, UpdatedBy = actorId, IsDeleted = false,
        }));

        var loginClaims = await dbContext.LoginBonusClaims.AsNoTracking()
            .Where(claim => claim.AccountId == sourceAccountId && !claim.IsDeleted).ToListAsync();
        await dbContext.LoginBonusClaims.AddRangeAsync(loginClaims.Select(claim => new LoginBonusClaimEntity
        {
            LoginBonusClaimId = Guid.NewGuid(), AccountId = targetAccountId, ClaimDate = claim.ClaimDate,
            ClaimedAt = claim.ClaimedAt, CreatedAt = now, UpdatedAt = now,
            CreatedBy = actorId, UpdatedBy = actorId, IsDeleted = false,
        }));

        var marketState = await dbContext.MarketAccountStates.AsNoTracking()
            .SingleOrDefaultAsync(state => state.AccountId == sourceAccountId && !state.IsDeleted);
        if (marketState is not null)
        {
            await dbContext.MarketAccountStates.AddAsync(new MarketAccountStateEntity
            {
                AccountId = targetAccountId, CompletedTradeCount = marketState.CompletedTradeCount, Tier = marketState.Tier,
                MaxActiveListingCount = marketState.MaxActiveListingCount, SuspendedUntil = marketState.SuspendedUntil,
                CreatedAt = now, UpdatedAt = now, CreatedBy = actorId, UpdatedBy = actorId, IsDeleted = false,
            });
        }
    }

    private static string RemapLearnedSkillBindings(string? json, IReadOnlyDictionary<Guid, Guid> learnedSkillIds)
    {
        if (string.IsNullOrWhiteSpace(json))
            return "[]";
        try
        {
            var bindings = JsonSerializer.Deserialize<List<string?>>(json) ?? [];
            return JsonSerializer.Serialize(bindings.Select(binding => RemapLearnedSkillBinding(binding, learnedSkillIds)));
        }
        catch (JsonException)
        {
            return json;
        }
    }

    private static string? RemapLearnedSkillBinding(string? binding, IReadOnlyDictionary<Guid, Guid> learnedSkillIds)
    {
        if (!Guid.TryParse(binding, out var sourceLearnedSkillId))
            return binding;
        return learnedSkillIds.TryGetValue(sourceLearnedSkillId, out var targetLearnedSkillId)
            ? targetLearnedSkillId.ToString()
            : null;
    }

    public async Task<AccountResponse?> UpdateAsync(Guid uuid, AccountUpdateRequest request)
    {
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
        var accounts = dbContext.Database.IsSqlServer()
            ? dbContext.Accounts.FromSqlInterpolated($"""
                SELECT * FROM [dbo].[account] WITH (UPDLOCK, HOLDLOCK)
                WHERE [uuid] = {uuid} AND [is_deleted] = 0
                """)
            : dbContext.Accounts.Where(x => x.Uuid == uuid && !x.IsDeleted);
        var account = await accounts
            .Include(x => x.ClassProgresses)
            .SingleOrDefaultAsync();

        if (account is null)
            return null;

        var progressRequested = request.Mode.HasValue
            || request.Level.HasValue || request.TotalExperience.HasValue
            || request.ClassId is not null || request.ClassLevel.HasValue || request.ClassExperience.HasValue
            || request.ClassProgresses is not null;

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
            account.HighestLevel = Math.Max(account.HighestLevel, account.Level);
            if (account.RebirthOriginalLevel.HasValue && account.Level >= account.RebirthOriginalLevel.Value)
            {
                account.RebirthOriginalLevel = null;
                account.RebirthExperienceRemainder = 0;
            }
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
        if (progressRequested)
            account.ProgressVersion = Math.Max(1, account.ProgressVersion + 1);

        await dbContext.SaveChangesAsync();

        return MapToResponse(account);
    }

    private async Task<string> ResolveGeneratedAccountNameAsync(string requestedName)
    {
        var baseName = NormalizeGeneratedAccountName(requestedName);
        if (!await AccountNameExistsAsync(baseName))
            return baseName;

        for (var suffixIndex = 1; suffixIndex < int.MaxValue; suffixIndex++)
        {
            var suffix = suffixIndex.ToString(System.Globalization.CultureInfo.InvariantCulture);
            var prefixLength = Math.Max(3, AccountNameMaxLength - suffix.Length);
            var candidate = baseName[..Math.Min(prefixLength, baseName.Length)] + suffix;
            if (!await AccountNameExistsAsync(candidate))
                return candidate;
        }

        throw new InvalidOperationException("No generated account name is available.");
    }

    private static bool IsRetryOfCreateRequest(string existingName, string requestedName)
    {
        var baseName = NormalizeGeneratedAccountName(requestedName);
        if (string.Equals(existingName, baseName, StringComparison.Ordinal))
            return true;
        if (!existingName.StartsWith(baseName, StringComparison.Ordinal))
            return false;

        var suffix = existingName[baseName.Length..];
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
        if (accountName.Length is < 3 or > AccountNameMaxLength
            || accountName.Any(character =>
                !((character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '_')))
            throw new ArgumentException("Account name must contain only ASCII letters, digits, or underscore and be 3-50 characters long.");
    }

    private static string NormalizeGeneratedAccountName(string requestedName)
    {
        var characters = (requestedName ?? string.Empty)
            .Where(character => (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '_')
            .Take(AccountNameMaxLength)
            .ToArray();
        var normalized = new string(characters);
        if (normalized.Length < 3)
            normalized = "Player";
        return normalized;
    }

    private async Task<int> FindLowestAvailableSlotAsync(Guid userId)
    {
        var occupiedSlots = await dbContext.Accounts
            .Where(account => account.UserId == userId && !account.IsDeleted)
            .Select(account => account.SlotIndex)
            .ToListAsync();
        var occupied = occupiedSlots.ToHashSet();
        for (var slot = 0; slot <= 99; slot++)
        {
            if (!occupied.Contains(slot))
                return slot;
        }
        return -1;
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
        var account = await FindAccountForDeleteAsync(uuid);
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

    private async Task<AccountEntity?> FindAccountForDeleteAsync(Guid uuid)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.Accounts
                .FromSqlInterpolated($"""
                    SELECT *
                    FROM [dbo].[account] WITH (UPDLOCK, HOLDLOCK)
                    WHERE [uuid] = {uuid}
                    """)
                .SingleOrDefaultAsync();
        }

        return await dbContext.Accounts
            .FirstOrDefaultAsync(candidate => candidate.Uuid == uuid);
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
        // 編集案・lease・Plugin評価viewはアカウント固有の短期状態であり、削除済み
        // アカウントに対して後から確定させない。マーケット等の履歴は保持する。
        await dbContext.SkillTreeServerPlayerViews
            .Where(entity => entity.AccountId == accountId)
            .ExecuteDeleteAsync();
        await dbContext.SkillTreeAccountSessions.Where(entity => entity.AccountId == accountId)
            .ExecuteUpdateAsync(setters => setters.SetProperty(entity => entity.Closed, true));
        await dbContext.SkillTreeOperations
            .Where(entity => entity.AccountId == accountId)
            .ExecuteDeleteAsync();
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
        await LockAccountInventoriesForUpdateAsync(accountId);
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
        await dbContext.MarketWebPurchases
            .Where(entity => entity.BuyerAccountId == accountId && entity.Status == "PENDING")
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(entity => entity.Status, "FAILED")
                .SetProperty(entity => entity.ErrorCode, "market.buyer_deleted")
                .SetProperty(entity => entity.UpdatedAt, deletedAt));
        await dbContext.MarketListings
            .Where(entity => entity.SellerAccountId == accountId && entity.Status == "ACTIVE" && !entity.IsDeleted)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(entity => entity.Status, "CANCELED")
                .SetProperty(entity => entity.StatusReason, "ACCOUNT_DELETED")
                .SetProperty(entity => entity.CanceledAt, deletedAt)
                .SetProperty(entity => entity.UpdatedAt, deletedAt)
                .SetProperty(entity => entity.UpdatedBy, deletedBy));
    }

    private async Task LockAccountInventoriesForUpdateAsync(Guid accountId)
    {
        if (!dbContext.Database.IsSqlServer())
            return;

        await dbContext.Inventories
            .FromSqlInterpolated($"""
                SELECT *
                FROM [dbo].[inventory] WITH (UPDLOCK, HOLDLOCK)
                WHERE [account_id] = {accountId} AND [is_deleted] = 0
                ORDER BY [inventory_id]
                """)
            .AsNoTracking()
            .ToListAsync();
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
        ProgressVersion = 1,
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
            HighestLevel = account.HighestLevel,
            RebirthOriginalLevel = account.RebirthOriginalLevel,
            RebirthExperienceRemainder = account.RebirthExperienceRemainder,
            ClassId = account.ClassId,
            ClassLevel = account.ClassLevel,
            ClassExperience = account.ClassExperience,
            ProgressVersion = account.ProgressVersion,
            ClassProgresses = progresses,
            CreatedAt = account.CreatedAt,
            UpdatedAt = account.UpdatedAt,
            CreatedBy = account.CreatedBy,
            UpdatedBy = account.UpdatedBy,
            IsDeleted = account.IsDeleted,
        };
    }
}
