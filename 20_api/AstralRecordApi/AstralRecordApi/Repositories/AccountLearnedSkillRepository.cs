using System.Data;
using System.Text.Json;
using System.Text.Json.Nodes;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class AccountLearnedSkillRepository(
    AstralRecordDbContext dbContext,
    MasterDataDbContext masterDataDbContext) : IAccountLearnedSkillRepository
{
    private const string SkillMasterType = "skill";
    private const string ItemMasterType = "item";
    private const string SkillGemCategory = "skill_gem";
    private const string SigilCategory = "sigil";
    private const string OrbCategory = "orb";
    private const string SkillGemIdPrefix = "00_skill_gem_";
    private const string SigilAttachOrbEffectType = "SIGIL_ATTACH";
    private const string SigilDetachOrbEffectType = "SIGIL_DETACH";

    public async Task<IReadOnlyList<AccountLearnedSkillResponse>> GetByAccountIdAsync(Guid accountId)
    {
        var accountExists = await dbContext.Accounts
            .AsNoTracking()
            .AnyAsync(candidate => candidate.Uuid == accountId && !candidate.IsDeleted);
        if (!accountExists)
            throw new KeyNotFoundException($"Account not found: {accountId}");

        var skills = await GetSkillMastersAsync();
        var sigils = await GetSigilMastersAsync();
        await ReconcileAsync(accountId, skills, sigils);

        return (await dbContext.AccountLearnedSkills
                .AsNoTracking()
                .Include(skill => skill.Sigils.Where(sigil => !sigil.IsDeleted))
                .Where(skill => skill.AccountId == accountId && !skill.IsDeleted)
                .OrderBy(skill => skill.CreatedAt)
                .ThenBy(skill => skill.LearnedSkillId)
                .ToListAsync())
            .Select(Map)
            .ToArray();
    }

    public async Task<AccountLearnedSkillMutationResult> LearnAsync(
        Guid accountId,
        AccountLearnedSkillLearnRequest request)
    {
        var skillId = NormalizeId(request.SkillId);
        var skill = await GetSkillAsync(skillId);
        if (skill is null)
            return Failure(AccountLearnedSkillMutationFailure.SkillNotFound);
        if (!await AccountExistsAsync(accountId))
            return Failure(AccountLearnedSkillMutationFailure.AccountNotFound);

        return await ExecuteSerializableAsync(async () =>
        {
            var material = await FindOwnedMaterialAsync(accountId, request.GemInventoryEntryId);
            if (!IsExpectedMaterial(material, SkillGemCategory, SkillGemIdPrefix + skill.Id))
                return Failure(AccountLearnedSkillMutationFailure.InvalidMaterial);

            var now = DateTime.UtcNow;
            var entity = new AccountLearnedSkillEntity
            {
                LearnedSkillId = Guid.NewGuid(),
                AccountId = accountId,
                SkillId = skill.Id,
                Level = 1,
                Version = 1,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = request.UpdatedBy,
                UpdatedBy = request.UpdatedBy,
                IsDeleted = false,
            };
            await dbContext.AccountLearnedSkills.AddAsync(entity);
            ConsumeMaterial(material!, request.UpdatedBy, now);
            await dbContext.SaveChangesAsync();
            return Success(Map(entity));
        });
    }

    public async Task<AccountLearnedSkillMutationResult> LevelUpAsync(
        Guid accountId,
        Guid learnedSkillId,
        AccountLearnedSkillLevelUpRequest request)
    {
        return await ExecuteSerializableAsync(async () =>
        {
            var entity = await FindLearnedSkillAsync(accountId, learnedSkillId);
            if (entity is null)
                return Failure(AccountLearnedSkillMutationFailure.LearnedSkillNotFound);
            var skill = await GetSkillAsync(entity.SkillId);
            if (skill is null)
                return Failure(AccountLearnedSkillMutationFailure.SkillNotFound);
            if (entity.Level >= Math.Max(1, skill.MaxLevel))
                return Failure(AccountLearnedSkillMutationFailure.MaxLevelReached);

            var material = await FindOwnedMaterialAsync(accountId, request.GemInventoryEntryId);
            if (!IsExpectedMaterial(material, SkillGemCategory, SkillGemIdPrefix + skill.Id))
                return Failure(AccountLearnedSkillMutationFailure.InvalidMaterial);

            var now = DateTime.UtcNow;
            entity.Level += 1;
            entity.Version += 1;
            entity.UpdatedAt = now;
            entity.UpdatedBy = request.UpdatedBy;
            ConsumeMaterial(material!, request.UpdatedBy, now);
            await dbContext.SaveChangesAsync();
            return Success(Map(entity));
        });
    }

    public async Task<AccountLearnedSkillMutationResult> AttachSigilAsync(
        Guid accountId,
        Guid learnedSkillId,
        AccountLearnedSkillAttachSigilRequest request)
    {
        var requestedSigilId = NormalizeId(request.SigilId);
        var sigil = await GetSigilAsync(requestedSigilId);
        if (sigil is null)
            return Failure(AccountLearnedSkillMutationFailure.SigilNotFound);

        return await ExecuteSerializableAsync(async () =>
        {
            var entity = await FindLearnedSkillAsync(accountId, learnedSkillId);
            if (entity is null)
                return Failure(AccountLearnedSkillMutationFailure.LearnedSkillNotFound);
            var skill = await GetSkillAsync(entity.SkillId);
            if (skill is null)
                return Failure(AccountLearnedSkillMutationFailure.SkillNotFound);
            if (!skill.AllowedSigilIds.Any(id => IdEquals(id, requestedSigilId)))
                return Failure(AccountLearnedSkillMutationFailure.SigilNotAllowed);

            var activeSigils = entity.Sigils.Where(attached => !attached.IsDeleted).ToArray();
            if (activeSigils.Any(attached => IdEquals(attached.EquipGroupId, sigil.EquipGroupId)))
                return Failure(AccountLearnedSkillMutationFailure.DuplicateSigilGroup);
            var slotCount = ResolveSigilSlotCount(skill, entity.Level);
            if (activeSigils.Length >= slotCount)
                return Failure(AccountLearnedSkillMutationFailure.NoSigilSlot);

            var material = await FindOwnedMaterialAsync(accountId, request.SigilInventoryEntryId);
            if (!IsExpectedMaterial(material, SigilCategory, requestedSigilId))
                return Failure(AccountLearnedSkillMutationFailure.InvalidMaterial);
            var orb = await FindOwnedMaterialAsync(accountId, request.OrbInventoryEntryId);
            var orbItem = await GetOrbItemAsync(orb?.ItemId);
            if (!IsExpectedOrb(orb, orbItem, SigilAttachOrbEffectType))
                return Failure(AccountLearnedSkillMutationFailure.InvalidMaterial);

            var occupiedSlots = activeSigils.Select(attached => attached.SlotIndex).ToHashSet();
            var slotIndex = Enumerable.Range(0, slotCount).First(index => !occupiedSlots.Contains(index));
            var now = DateTime.UtcNow;
            var attachedSigil = new AccountLearnedSkillSigilEntity
            {
                LearnedSkillSigilId = Guid.NewGuid(),
                LearnedSkillId = entity.LearnedSkillId,
                SigilId = requestedSigilId,
                EquipGroupId = sigil.EquipGroupId,
                SlotIndex = slotIndex,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = request.UpdatedBy,
                UpdatedBy = request.UpdatedBy,
                IsDeleted = false,
            };
            entity.Sigils.Add(attachedSigil);
            dbContext.Entry(attachedSigil).State = EntityState.Added;
            entity.Version += 1;
            entity.UpdatedAt = now;
            entity.UpdatedBy = request.UpdatedBy;
            ConsumeMaterial(material!, request.UpdatedBy, now);
            ConsumeMaterial(orb!, request.UpdatedBy, now);
            await dbContext.SaveChangesAsync();
            return Success(Map(entity));
        });
    }

    public async Task<AccountLearnedSkillMutationResult> DetachSigilAsync(
        Guid accountId,
        Guid learnedSkillId,
        Guid learnedSkillSigilId,
        AccountLearnedSkillDetachSigilRequest request)
    {
        return await ExecuteSerializableAsync(async () =>
        {
            var entity = await FindLearnedSkillAsync(accountId, learnedSkillId);
            if (entity is null)
                return Failure(AccountLearnedSkillMutationFailure.LearnedSkillNotFound);

            var attached = entity.Sigils.FirstOrDefault(candidate =>
                candidate.LearnedSkillSigilId == learnedSkillSigilId && !candidate.IsDeleted);
            if (attached is null)
                return Failure(AccountLearnedSkillMutationFailure.SigilAttachmentNotFound);

            var sigilItem = await GetSigilItemAsync(attached.SigilId);
            if (sigilItem?.Sigil is null)
                return Failure(AccountLearnedSkillMutationFailure.SigilNotFound);
            var orb = await FindOwnedMaterialAsync(accountId, request.OrbInventoryEntryId);
            var orbItem = await GetOrbItemAsync(orb?.ItemId);
            if (!IsExpectedOrb(orb, orbItem, SigilDetachOrbEffectType))
                return Failure(AccountLearnedSkillMutationFailure.InvalidMaterial);

            var bag = await dbContext.Inventories.FirstOrDefaultAsync(inventory =>
                inventory.AccountId == accountId
                && !inventory.IsDeleted
                && inventory.IsEnabled
                && inventory.InventoryProfile == "GAME"
                && inventory.InventoryType == "BAG");
            if (bag is null)
                return Failure(AccountLearnedSkillMutationFailure.InventoryNotFound);

            var now = DateTime.UtcNow;
            var maxStack = Math.Max(1, sigilItem.MaxStack);
            var bagEntries = await dbContext.InventoryEntries
                .Where(entry => entry.InventoryId == bag.InventoryId
                    && !entry.IsDeleted
                    && entry.InstanceId == null
                    && (entry.InstanceType == null || entry.InstanceType == string.Empty))
                .OrderBy(entry => entry.SlotIndex ?? int.MaxValue)
                .ThenBy(entry => entry.CreatedAt)
                .ToArrayAsync();
            var returnedEntry = bagEntries.FirstOrDefault(entry =>
                entry.Quantity < maxStack
                && IdEquals(entry.ItemCategory, SigilCategory)
                && IdEquals(entry.ItemId, attached.SigilId));
            if (returnedEntry is null)
            {
                returnedEntry = new InventoryEntryEntity
                {
                    InventoryEntryId = Guid.NewGuid(),
                    InventoryId = bag.InventoryId,
                    SlotIndex = null,
                    ItemCategory = SigilCategory,
                    ItemId = NormalizeId(attached.SigilId),
                    Quantity = 1,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = request.UpdatedBy,
                    UpdatedBy = request.UpdatedBy,
                    IsDeleted = false,
                };
                await dbContext.InventoryEntries.AddAsync(returnedEntry);
            }
            else
            {
                returnedEntry.Quantity += 1;
                returnedEntry.UpdatedAt = now;
                returnedEntry.UpdatedBy = request.UpdatedBy;
            }

            attached.IsDeleted = true;
            attached.UpdatedAt = now;
            attached.UpdatedBy = request.UpdatedBy;
            entity.Version += 1;
            entity.UpdatedAt = now;
            entity.UpdatedBy = request.UpdatedBy;
            ConsumeMaterial(orb!, request.UpdatedBy, now);
            await dbContext.SaveChangesAsync();
            return Success(Map(entity), returnedEntry.InventoryEntryId);
        });
    }

    public async Task<AccountLearnedSkillMutationResult> ForgetAsync(
        Guid accountId,
        Guid learnedSkillId,
        AccountLearnedSkillForgetRequest request)
    {
        return await ExecuteSerializableAsync(async () =>
        {
            var entity = await FindLearnedSkillAsync(accountId, learnedSkillId);
            if (entity is null)
                return Failure(AccountLearnedSkillMutationFailure.LearnedSkillNotFound);

            var now = DateTime.UtcNow;
            var result = Success(Map(entity));
            entity.IsDeleted = true;
            entity.Version += 1;
            entity.UpdatedAt = now;
            entity.UpdatedBy = request.UpdatedBy;
            foreach (var sigil in entity.Sigils.Where(sigil => !sigil.IsDeleted))
            {
                sigil.IsDeleted = true;
                sigil.UpdatedAt = now;
                sigil.UpdatedBy = request.UpdatedBy;
            }

            await dbContext.SaveChangesAsync();
            return result;
        });
    }

    private async Task<AccountLearnedSkillMutationResult> ExecuteSerializableAsync(
        Func<Task<AccountLearnedSkillMutationResult>> operation)
    {
        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            await using var transaction = await dbContext.Database
                .BeginTransactionAsync(IsolationLevel.Serializable);
            var result = await operation();
            if (result.Succeeded)
                await transaction.CommitAsync();
            return result;
        });
    }

    private async Task<bool> AccountExistsAsync(Guid accountId)
        => await dbContext.Accounts.AsNoTracking().AnyAsync(account => account.Uuid == accountId && !account.IsDeleted);

    private async Task<AccountLearnedSkillEntity?> FindLearnedSkillAsync(Guid accountId, Guid learnedSkillId)
        => await dbContext.AccountLearnedSkills
            .Include(skill => skill.Sigils)
            .FirstOrDefaultAsync(skill => skill.LearnedSkillId == learnedSkillId
                && skill.AccountId == accountId
                && !skill.IsDeleted);

    private async Task<InventoryEntryEntity?> FindOwnedMaterialAsync(Guid accountId, Guid inventoryEntryId)
        => await (from entry in dbContext.InventoryEntries
                  join inventory in dbContext.Inventories on entry.InventoryId equals inventory.InventoryId
                  where entry.InventoryEntryId == inventoryEntryId
                        && !entry.IsDeleted
                        && entry.Quantity > 0
                        && !inventory.IsDeleted
                        && inventory.IsEnabled
                        && inventory.InventoryProfile == "GAME"
                        && inventory.AccountId == accountId
                  select entry).FirstOrDefaultAsync();

    private static bool IsExpectedMaterial(InventoryEntryEntity? entry, string category, string itemId)
        => entry is not null
            && IdEquals(entry.ItemCategory, category)
            && IdEquals(entry.ItemId, itemId)
            && entry.Quantity > 0
            && (!IdEquals(category, SkillGemCategory) || entry.Quantity == 1);

    private static bool IsExpectedOrb(
        InventoryEntryEntity? entry,
        ItemResponse? item,
        string expectedEffectType)
        => entry is not null
            && item?.Orb?.Effect is not null
            && IdEquals(entry.ItemCategory, OrbCategory)
            && entry.Quantity > 0
            && entry.InstanceId is null
            && string.IsNullOrWhiteSpace(entry.InstanceType)
            && string.Equals(item.Orb.Effect.Type, expectedEffectType, StringComparison.OrdinalIgnoreCase);

    private static void ConsumeMaterial(InventoryEntryEntity entry, Guid updatedBy, DateTime now)
    {
        if (entry.Quantity > 1)
            entry.Quantity -= 1;
        else
            entry.IsDeleted = true;
        entry.UpdatedAt = now;
        entry.UpdatedBy = updatedBy;
    }

    private async Task<SkillResponse?> GetSkillAsync(string skillId)
    {
        var payload = await masterDataDbContext.Entries.AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && entry.MasterType == SkillMasterType
                && entry.MasterId == skillId)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefaultAsync();
        return payload is null ? null : MasterDataPayloadJson.Deserialize<SkillResponse>(payload);
    }

    private async Task<IReadOnlyDictionary<string, SkillResponse>> GetSkillMastersAsync()
    {
        var entries = await masterDataDbContext.Entries.AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.MasterType == SkillMasterType)
            .Select(entry => new { entry.MasterId, entry.PayloadJson })
            .ToArrayAsync();
        return entries
            .Select(entry => new
            {
                Id = NormalizeId(entry.MasterId),
                Skill = MasterDataPayloadJson.Deserialize<SkillResponse>(entry.PayloadJson),
            })
            .Where(entry => entry.Skill is not null && !string.IsNullOrWhiteSpace(entry.Id))
            .ToDictionary(entry => entry.Id, entry => entry.Skill!, StringComparer.OrdinalIgnoreCase);
    }

    private async Task<IReadOnlyDictionary<string, ItemSigilResponse>> GetSigilMastersAsync()
    {
        var entries = await masterDataDbContext.Entries.AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && entry.MasterType == ItemMasterType
                && entry.Category == SigilCategory)
            .Select(entry => new { entry.MasterId, entry.PayloadJson })
            .ToArrayAsync();
        return entries
            .Select(entry => new
            {
                Id = NormalizeId(entry.MasterId),
                Sigil = DeserializeSigil(entry.PayloadJson),
            })
            .Where(entry => entry.Sigil is not null && !string.IsNullOrWhiteSpace(entry.Id))
            .ToDictionary(entry => entry.Id, entry => entry.Sigil!, StringComparer.OrdinalIgnoreCase);
    }

    private async Task<ItemSigilResponse?> GetSigilAsync(string sigilId)
    {
        return (await GetSigilItemAsync(sigilId))?.Sigil;
    }

    private async Task<ItemResponse?> GetSigilItemAsync(string sigilId)
    {
        var entry = await masterDataDbContext.Entries.AsNoTracking()
            .Where(candidate => !candidate.IsDeleted
                && candidate.MasterType == ItemMasterType
                && candidate.Category == SigilCategory
                && candidate.MasterId == sigilId)
            .Select(candidate => candidate.PayloadJson)
            .FirstOrDefaultAsync();
        if (entry is null)
            return null;
        var node = JsonNode.Parse(entry)?.AsObject();
        if (node is null)
            return null;
        node["category"] = SigilCategory;
        return MasterDataPayloadJson.Deserialize<ItemResponse>(node.ToJsonString());
    }

    private async Task<ItemResponse?> GetOrbItemAsync(string? orbId)
    {
        var normalizedOrbId = NormalizeId(orbId);
        if (string.IsNullOrWhiteSpace(normalizedOrbId))
            return null;
        var entry = await masterDataDbContext.Entries.AsNoTracking()
            .Where(candidate => !candidate.IsDeleted
                && candidate.MasterType == ItemMasterType
                && candidate.Category == OrbCategory
                && candidate.MasterId == normalizedOrbId)
            .Select(candidate => candidate.PayloadJson)
            .FirstOrDefaultAsync();
        if (entry is null)
            return null;
        var node = JsonNode.Parse(entry)?.AsObject();
        if (node is null)
            return null;
        node["category"] = OrbCategory;
        return MasterDataPayloadJson.Deserialize<ItemResponse>(node.ToJsonString());
    }

    private async Task ReconcileAsync(
        Guid accountId,
        IReadOnlyDictionary<string, SkillResponse> skills,
        IReadOnlyDictionary<string, ItemSigilResponse> sigils)
    {
        var strategy = dbContext.Database.CreateExecutionStrategy();
        await strategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            await ReconcileInTransactionAsync(accountId, skills, sigils);
            await transaction.CommitAsync();
        });
    }

    private async Task ReconcileInTransactionAsync(
        Guid accountId,
        IReadOnlyDictionary<string, SkillResponse> skills,
        IReadOnlyDictionary<string, ItemSigilResponse> sigils)
    {
        var learnedSkills = await dbContext.AccountLearnedSkills
            .Include(skill => skill.Sigils)
            .Where(skill => skill.AccountId == accountId && !skill.IsDeleted)
            .OrderBy(skill => skill.CreatedAt)
            .ThenBy(skill => skill.LearnedSkillId)
            .ToArrayAsync();

        var now = DateTime.UtcNow;
        var actor = accountId;
        var removedLearnedSkillIds = new HashSet<Guid>();
        var compensatedSigilIds = new List<string>();

        foreach (var learnedSkill in learnedSkills)
        {
            if (!skills.TryGetValue(NormalizeId(learnedSkill.SkillId), out var skillMaster))
            {
                learnedSkill.IsDeleted = true;
                learnedSkill.Version += 1;
                learnedSkill.UpdatedAt = now;
                learnedSkill.UpdatedBy = actor;
                removedLearnedSkillIds.Add(learnedSkill.LearnedSkillId);
                foreach (var attached in learnedSkill.Sigils.Where(attached => !attached.IsDeleted))
                    RemoveSigil(attached, compensatedSigilIds, actor, now);
                continue;
            }

            var changed = false;
            var normalizedLevel = Math.Clamp(learnedSkill.Level, 1, Math.Max(1, skillMaster.MaxLevel));
            if (learnedSkill.Level != normalizedLevel)
            {
                learnedSkill.Level = normalizedLevel;
                changed = true;
            }

            var slotCount = ResolveSigilSlotCount(skillMaster, learnedSkill.Level);
            var occupiedGroups = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var occupiedSlots = new HashSet<int>();
            foreach (var attached in learnedSkill.Sigils
                         .Where(attached => !attached.IsDeleted)
                         .OrderBy(attached => attached.SlotIndex)
                         .ThenBy(attached => attached.CreatedAt))
            {
                var sigilId = NormalizeId(attached.SigilId);
                var valid = sigils.TryGetValue(sigilId, out var sigilMaster)
                    && skillMaster.AllowedSigilIds.Any(allowed => IdEquals(allowed, sigilId))
                    && attached.SlotIndex >= 0
                    && attached.SlotIndex < slotCount
                    && occupiedSlots.Add(attached.SlotIndex)
                    && IdEquals(attached.EquipGroupId, sigilMaster!.EquipGroupId)
                    && occupiedGroups.Add(sigilMaster.EquipGroupId);
                if (valid)
                    continue;

                RemoveSigil(attached, compensatedSigilIds, actor, now);
                changed = true;
            }

            if (changed)
            {
                learnedSkill.Version += 1;
                learnedSkill.UpdatedAt = now;
                learnedSkill.UpdatedBy = actor;
            }
        }

        var validGemIds = skills.Keys
            .Select(skillId => SkillGemIdPrefix + skillId)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        var ownedGemEntries = await (from entry in dbContext.InventoryEntries
                                     join inventory in dbContext.Inventories
                                         on entry.InventoryId equals inventory.InventoryId
                                     where inventory.AccountId == accountId
                                           && !inventory.IsDeleted
                                           && !entry.IsDeleted
                                           && entry.ItemCategory == SkillGemCategory
                                     select entry).ToArrayAsync();
        foreach (var entry in ownedGemEntries.Where(entry => entry.ItemId is null
                     || !validGemIds.Contains(entry.ItemId)))
        {
            entry.IsDeleted = true;
            entry.UpdatedAt = now;
            entry.UpdatedBy = actor;
        }

        if (removedLearnedSkillIds.Count > 0)
            await RemoveDeletedBindingsAsync(accountId, removedLearnedSkillIds, actor, now);

        if (compensatedSigilIds.Count > 0)
            await AddSigilCompensationMailAsync(accountId, compensatedSigilIds, actor, now);

        await dbContext.SaveChangesAsync();
    }

    private async Task RemoveDeletedBindingsAsync(
        Guid accountId,
        IReadOnlySet<Guid> removedIds,
        Guid actor,
        DateTime now)
    {
        var presets = await dbContext.SkillBindPresets
            .Where(preset => preset.AccountId == accountId && !preset.IsDeleted)
            .ToArrayAsync();
        foreach (var preset in presets)
        {
            var active = DeserializeBindingSlots(preset.ActiveSkillSlotsJson);
            var passive = DeserializeBindingSlots(preset.PassiveSkillSlotsJson);
            var changed = ClearRemovedBindings(active, removedIds)
                | ClearRemovedBindings(passive, removedIds);
            if (TryParseRemovedBinding(preset.LeftClickSkillId, removedIds))
            {
                preset.LeftClickSkillId = SkillBindPresetRepository.WeaponNormalAttackBindingId;
                changed = true;
            }
            if (!changed)
                continue;

            preset.ActiveSkillSlotsJson = JsonSerializer.Serialize(active);
            preset.PassiveSkillSlotsJson = JsonSerializer.Serialize(passive);
            preset.Version += 1;
            preset.UpdatedAt = now;
            preset.UpdatedBy = actor;
        }
    }

    private async Task AddSigilCompensationMailAsync(
        Guid accountId,
        IReadOnlyCollection<string> sigilIds,
        Guid actor,
        DateTime now)
    {
        var mailId = $"skill-sigil-compensation-{Guid.NewGuid():N}";
        var rewards = sigilIds
            .GroupBy(NormalizeId, StringComparer.OrdinalIgnoreCase)
            .Where(group => !string.IsNullOrWhiteSpace(group.Key))
            .Select(group => new MailRewardResponse
            {
                ItemId = group.Key,
                Category = SigilCategory,
                Amount = group.Count(),
            })
            .ToArray();
        if (rewards.Length == 0)
            return;

        var mail = new MailResponse
        {
            SchemaVersion = 1,
            Id = mailId,
            Icon = "WRITABLE_BOOK",
            Title = "シジル設定変更のお詫び",
            Body = "マスタデータとの不整合により、装着済みシジルをスキルから取り外しました。対象のシジルを返却します。",
            PublishFrom = now,
            PublishTo = null,
            ReceiveOnRead = true,
            Rewards = rewards,
        };
        await dbContext.PlayerMailDeliveries.AddAsync(new PlayerMailDeliveryEntity
        {
            PlayerMailDeliveryId = Guid.NewGuid(),
            AccountId = accountId,
            MailId = mailId,
            PayloadJson = JsonSerializer.Serialize(mail, MasterDataPayloadJson.Options),
            Version = 1,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = actor,
            UpdatedBy = actor,
            IsDeleted = false,
        });
    }

    private static ItemSigilResponse? DeserializeSigil(string payloadJson)
    {
        var node = JsonNode.Parse(payloadJson)?.AsObject();
        if (node is null)
            return null;
        node["category"] = SigilCategory;
        return MasterDataPayloadJson.Deserialize<ItemResponse>(node.ToJsonString())?.Sigil;
    }

    private static void RemoveSigil(
        AccountLearnedSkillSigilEntity attached,
        ICollection<string> compensatedSigilIds,
        Guid actor,
        DateTime now)
    {
        attached.IsDeleted = true;
        attached.UpdatedAt = now;
        attached.UpdatedBy = actor;
        compensatedSigilIds.Add(attached.SigilId);
    }

    private static List<string?> DeserializeBindingSlots(string? json)
    {
        try
        {
            return JsonSerializer.Deserialize<List<string?>>(json ?? "[]") ?? [];
        }
        catch (JsonException)
        {
            return [];
        }
    }

    private static bool ClearRemovedBindings(IList<string?> slots, IReadOnlySet<Guid> removedIds)
    {
        var changed = false;
        for (var index = 0; index < slots.Count; index++)
        {
            if (!TryParseRemovedBinding(slots[index], removedIds))
                continue;
            slots[index] = null;
            changed = true;
        }
        return changed;
    }

    private static bool TryParseRemovedBinding(string? value, IReadOnlySet<Guid> removedIds)
        => Guid.TryParse(value, out var learnedSkillId) && removedIds.Contains(learnedSkillId);

    private static int ResolveSigilSlotCount(SkillResponse skill, int level)
        => Math.Max(0, skill.SigilSlotsByLevel
            .Where(entry => entry.Level <= level)
            .OrderByDescending(entry => entry.Level)
            .Select(entry => entry.Slots)
            .FirstOrDefault());

    private static AccountLearnedSkillResponse Map(AccountLearnedSkillEntity entity) => new()
    {
        LearnedSkillId = entity.LearnedSkillId,
        AccountId = entity.AccountId,
        SkillId = entity.SkillId,
        Level = entity.Level,
        Sigils = entity.Sigils
            .Where(sigil => !sigil.IsDeleted)
            .OrderBy(sigil => sigil.SlotIndex)
            .Select(sigil => new AccountLearnedSkillSigilResponse
            {
                LearnedSkillSigilId = sigil.LearnedSkillSigilId,
                SigilId = sigil.SigilId,
                EquipGroupId = sigil.EquipGroupId,
                SlotIndex = sigil.SlotIndex,
            })
            .ToArray(),
        Version = entity.Version,
        CreatedAt = entity.CreatedAt,
        UpdatedAt = entity.UpdatedAt,
    };

    private static string NormalizeId(string? value) => value?.Trim().ToLowerInvariant() ?? string.Empty;
    private static bool IdEquals(string? left, string? right)
        => string.Equals(left?.Trim(), right?.Trim(), StringComparison.OrdinalIgnoreCase);
    private static AccountLearnedSkillMutationResult Success(
        AccountLearnedSkillResponse skill,
        Guid? returnedInventoryEntryId = null)
        => new(skill, AccountLearnedSkillMutationFailure.None, returnedInventoryEntryId);
    private static AccountLearnedSkillMutationResult Failure(AccountLearnedSkillMutationFailure failure)
        => new(null, failure);
}
