using System.Data;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>
/// Plugin がローカルで確定した state を保存する transaction 境界です。
/// 個別のゲーム操作を再計算・再消費せず、期待版と所有者を検証した snapshot だけを適用します。
/// </summary>
public sealed class PlayerStateSnapshotRepository(AstralRecordDbContext dbContext) : IPlayerStateSnapshotRepository
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public async Task<PlayerStateSnapshotSaveResult> SaveAsync(PlayerStateSnapshotSaveRequest request)
    {
        if (!TryValidateRoot(request, out var validationError))
            return Failure(PlayerStateSnapshotSaveFailure.Invalid, validationError ?? "Snapshot is invalid.");

        var requestHash = ComputeRequestHash(request);
        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);

            var existing = await FindSnapshotForUpdateAsync(request.SnapshotId);
            if (existing is not null)
            {
                await transaction.CommitAsync();
                if (existing.AccountId != request.AccountId || !string.Equals(existing.RequestHash, requestHash, StringComparison.Ordinal))
                    return Failure(PlayerStateSnapshotSaveFailure.Conflict, "snapshotId is already associated with another payload.");

                var replay = JsonSerializer.Deserialize<PlayerStateSnapshotAck>(existing.AckPayloadJson, JsonOptions);
                return replay is null
                    ? Failure(PlayerStateSnapshotSaveFailure.Conflict, "Stored snapshot acknowledgement is invalid.")
                    : Success(replay);
            }

            var account = await FindAccountForUpdateAsync(request.AccountId);
            if (account is null)
                return Failure(PlayerStateSnapshotSaveFailure.AccountNotFound, "Account was not found.");

            var now = RoundToMilliseconds(DateTime.UtcNow);
            var accountInventories = await FindAccountInventoriesForUpdateAsync(request.AccountId);
            var accountEntries = await FindAccountEntriesForUpdateAsync(request.AccountId);
            var result = await ApplyCoreStateAsync(request, accountInventories, accountEntries, now);
            if (!result.Succeeded)
                return result;

            var sectionResult = await ApplySectionsAsync(request, account, result.Ack!, now);
            if (!sectionResult.Succeeded)
                return sectionResult;
            result = sectionResult;

            await dbContext.SaveChangesAsync();
            var ack = await ReadCoreAckAsync(request, result.Ack!, now);
            dbContext.PlayerStateSnapshots.Add(new PlayerStateSnapshotEntity
            {
                SnapshotId = request.SnapshotId,
                AccountId = request.AccountId,
                RequestHash = requestHash,
                AckPayloadJson = JsonSerializer.Serialize(ack, JsonOptions),
                CreatedAt = now,
                CompletedAt = now,
                CreatedBy = request.UpdatedBy,
            });
            await dbContext.SaveChangesAsync();
            await transaction.CommitAsync();
            return Success(ack);
        });
    }

    private async Task<PlayerStateSnapshotSaveResult> ApplyCoreStateAsync(
        PlayerStateSnapshotSaveRequest request,
        IReadOnlyList<InventoryEntity> accountInventories,
        IReadOnlyList<InventoryEntryEntity> accountEntries,
        DateTime now)
    {
        var inventoriesById = accountInventories.ToDictionary(inventory => inventory.InventoryId);
        if (request.Inventories.Any(snapshot => !inventoriesById.ContainsKey(snapshot.InventoryId)))
            return Failure(PlayerStateSnapshotSaveFailure.Conflict, "Inventory ownership conflict.");

        var entriesById = accountEntries.ToDictionary(entry => entry.InventoryEntryId);
        var expectedEntriesById = request.Inventories.SelectMany(inventory => inventory.ExpectedEntries)
            .GroupBy(entry => entry.InventoryEntryId).ToDictionary(group => group.Key, group => group.ToArray());
        if (expectedEntriesById.Any(pair => pair.Key == Guid.Empty || pair.Value.Length != 1))
            return Failure(PlayerStateSnapshotSaveFailure.Invalid, "expectedEntries contains an invalid or duplicated inventoryEntryId.");
        var requestedEntryIds = request.Inventories.SelectMany(inventory => inventory.Entries)
            .Select(entry => entry.InventoryEntryId)
            .ToArray();
        if (requestedEntryIds.Distinct().Count() != requestedEntryIds.Length)
            return Failure(PlayerStateSnapshotSaveFailure.Invalid, "inventoryEntryId is duplicated in the snapshot.");

        var nonOwnedEntryIds = requestedEntryIds
            .Where(entryId => !entriesById.ContainsKey(entryId))
            .ToArray();
        if (nonOwnedEntryIds.Length > 0
            && await dbContext.InventoryEntries.AsNoTracking()
                .AnyAsync(entry => nonOwnedEntryIds.Contains(entry.InventoryEntryId)))
            return Failure(PlayerStateSnapshotSaveFailure.Conflict, "inventoryEntryId belongs to another account.");

        foreach (var inventorySnapshot in request.Inventories)
        {
            var inventory = inventoriesById[inventorySnapshot.InventoryId];
            var currentEntries = accountEntries.Where(entry => entry.InventoryId == inventory.InventoryId && !entry.IsDeleted)
                .OrderBy(entry => entry.InventoryEntryId).ToArray();
            var expectedEntries = inventorySnapshot.ExpectedEntries.OrderBy(entry => entry.InventoryEntryId).ToArray();
            if (expectedEntries.GroupBy(entry => entry.InventoryEntryId).Any(group => group.Count() != 1)
                || currentEntries.Length != expectedEntries.Length
                || currentEntries.Zip(expectedEntries).Any(pair =>
                    pair.First.InventoryEntryId != pair.Second.InventoryEntryId
                    || pair.First.UpdatedAt != pair.Second.UpdatedAt))
                return Failure(PlayerStateSnapshotSaveFailure.Conflict, "Inventory entry baseline snapshot is stale.");
            if (inventorySnapshot.MetadataDirty)
            {
                if (!inventorySnapshot.ExpectedUpdatedAt.HasValue || inventory.UpdatedAt != inventorySnapshot.ExpectedUpdatedAt.Value)
                    return Failure(PlayerStateSnapshotSaveFailure.Conflict, "Inventory metadata snapshot is stale.");
                if (!IsJsonOrNull(inventorySnapshot.MetadataJson))
                    return Failure(PlayerStateSnapshotSaveFailure.Invalid, "Inventory metadataJson is invalid.");
                inventory.MetadataJson = inventorySnapshot.MetadataJson;
                inventory.UpdatedAt = now;
                inventory.UpdatedBy = request.UpdatedBy;
            }

            foreach (var entrySnapshot in inventorySnapshot.Entries)
            {
                if (!IsValidEntry(entrySnapshot))
                    return Failure(PlayerStateSnapshotSaveFailure.Invalid, "Inventory entry payload is invalid.");
                if (!IsJsonOrNull(entrySnapshot.MetadataJson))
                    return Failure(PlayerStateSnapshotSaveFailure.Invalid, "Inventory entry metadataJson is invalid.");

                if (entriesById.TryGetValue(entrySnapshot.InventoryEntryId, out var existing))
                {
                    if (existing.IsDeleted
                        || !expectedEntriesById.ContainsKey(existing.InventoryEntryId)
                        || !entrySnapshot.ExpectedUpdatedAt.HasValue
                        || existing.UpdatedAt != entrySnapshot.ExpectedUpdatedAt.Value)
                        return Failure(PlayerStateSnapshotSaveFailure.Conflict, "Inventory entry snapshot is stale.");
                }
                else if (entrySnapshot.ExpectedUpdatedAt.HasValue)
                {
                    return Failure(PlayerStateSnapshotSaveFailure.Invalid, "New inventory entry must not specify expectedUpdatedAt.");
                }
            }
        }

        var equipmentById = await ApplyEquipmentAsync(request, now);
        if (equipmentById is null)
            return Failure(PlayerStateSnapshotSaveFailure.Conflict, "Equipment ownership or expectedUpdatedAt conflict.");

        var loadoutResult = await ApplyLoadoutsAsync(request, now);
        if (!loadoutResult)
            return Failure(PlayerStateSnapshotSaveFailure.Conflict, "Loadout ownership or expectedUpdatedAt conflict.");

        var entriesToDisable = accountEntries
            .Where(entry => !entry.IsDeleted && request.Inventories.Any(snapshot => snapshot.InventoryId == entry.InventoryId))
            .Concat(requestedEntryIds.Select(entryId => entriesById.GetValueOrDefault(entryId)).OfType<InventoryEntryEntity>())
            .DistinctBy(entry => entry.InventoryEntryId)
            .ToArray();
        foreach (var entry in entriesToDisable)
        {
            entry.IsDeleted = true;
            entry.UpdatedAt = now;
            entry.UpdatedBy = request.UpdatedBy;
        }
        if (entriesToDisable.Length > 0)
            await dbContext.SaveChangesAsync();

        foreach (var inventorySnapshot in request.Inventories)
        {
            foreach (var entrySnapshot in inventorySnapshot.Entries)
            {
                var entry = entriesById.GetValueOrDefault(entrySnapshot.InventoryEntryId);
                if (entry is null)
                {
                    entry = new InventoryEntryEntity
                    {
                        InventoryEntryId = entrySnapshot.InventoryEntryId,
                        CreatedAt = now,
                        CreatedBy = request.UpdatedBy,
                    };
                    await dbContext.InventoryEntries.AddAsync(entry);
                    entriesById.Add(entry.InventoryEntryId, entry);
                }

                var itemId = await ResolveEntryItemIdAsync(entrySnapshot, request.AccountId);
                if (itemId is null)
                    return Failure(PlayerStateSnapshotSaveFailure.Conflict, "Inventory equipment entry does not match owned equipment.");

                entry.InventoryId = inventorySnapshot.InventoryId;
                entry.SlotIndex = entrySnapshot.SlotIndex;
                entry.ItemCategory = entrySnapshot.ItemCategory.Trim();
                entry.ItemId = itemId;
                entry.InstanceType = string.IsNullOrWhiteSpace(entrySnapshot.InstanceType) ? null : entrySnapshot.InstanceType.Trim();
                entry.InstanceId = entrySnapshot.InstanceId;
                entry.Quantity = entrySnapshot.Quantity;
                entry.MetadataJson = entrySnapshot.MetadataJson;
                entry.IsDeleted = false;
                entry.UpdatedAt = now;
                entry.UpdatedBy = request.UpdatedBy;
            }
        }

        return Success(new PlayerStateSnapshotAck
        {
            SnapshotId = request.SnapshotId,
            AccountId = request.AccountId,
        });
    }

    private async Task<Dictionary<Guid, EquipmentInstanceEntity>?> ApplyEquipmentAsync(
        PlayerStateSnapshotSaveRequest request,
        DateTime now)
    {
        var ids = request.Equipment.Select(equipment => equipment.EquipmentInstanceId).ToArray();
        if (ids.Distinct().Count() != ids.Length)
            return null;

        var entities = await dbContext.EquipmentInstances
            .Where(entity => ids.Contains(entity.EquipmentInstanceId))
            .ToListAsync();
        if (entities.Count != ids.Length || entities.Any(entity => entity.AccountId != request.AccountId || entity.IsDeleted))
            return null;
        foreach (var equipmentId in ids)
        {
            if (await MarketListingRangeLock.HasActiveOrSuspendedAsync(dbContext, "EQUIPMENT", equipmentId))
                return null;
        }

        var byId = entities.ToDictionary(entity => entity.EquipmentInstanceId);
        foreach (var snapshot in request.Equipment)
        {
            var entity = byId[snapshot.EquipmentInstanceId];
            if (entity.UpdatedAt != snapshot.ExpectedUpdatedAt || !IsValidEquipment(snapshot))
                return null;

            entity.EnhanceLevel = snapshot.EnhanceLevel;
            entity.RuneMaxSlots = snapshot.RuneMaxSlots;
            entity.TranscendenceRank = snapshot.TranscendenceRank;
            entity.DurabilityMax = snapshot.DurabilityMax;
            entity.DurabilityValue = snapshot.DurabilityValue;
            entity.UpdatedAt = AdvanceUpdatedAt(entity.UpdatedAt, now);
            entity.UpdatedBy = request.UpdatedBy;

            var existingEnchants = await dbContext.EquipmentInstanceEnchants
                .Where(enchant => enchant.EquipmentInstanceId == entity.EquipmentInstanceId)
                .ToListAsync();
            var existingEnchantsById = existingEnchants.ToDictionary(enchant => enchant.EnchantId);
            var requestedEnchants = snapshot.Enchants.ToDictionary(enchant => enchant.EnchantId);
            dbContext.EquipmentInstanceEnchants.RemoveRange(existingEnchants.Where(enchant => !requestedEnchants.ContainsKey(enchant.EnchantId)));
            foreach (var enchantSnapshot in snapshot.Enchants)
            {
                if (!existingEnchantsById.TryGetValue(enchantSnapshot.EnchantId, out var enchant))
                {
                    enchant = new EquipmentInstanceEnchantEntity
                    {
                        EnchantId = enchantSnapshot.EnchantId,
                        EquipmentInstanceId = entity.EquipmentInstanceId,
                        CreatedAt = now,
                        CreatedBy = request.UpdatedBy,
                    };
                    await dbContext.EquipmentInstanceEnchants.AddAsync(enchant);
                }
                enchant.SlotIndex = enchantSnapshot.SlotIndex;
                enchant.EnchantMasterId = enchantSnapshot.EnchantMasterId.Trim();
                enchant.EffectId = enchantSnapshot.EffectId.Trim();
                enchant.Status = enchantSnapshot.Status.Trim();
                enchant.Type = enchantSnapshot.Type.Trim();
                enchant.Value = enchantSnapshot.Value;
                enchant.UpdatedAt = now;
                enchant.UpdatedBy = request.UpdatedBy;
            }

            var existingRunes = await dbContext.EquipmentInstanceRunes
                .Where(rune => rune.EquipmentInstanceId == entity.EquipmentInstanceId)
                .ToListAsync();
            var existingRunesById = existingRunes.ToDictionary(rune => rune.RuneId);
            var requestedRunes = snapshot.Runes.ToDictionary(rune => rune.RuneId);
            dbContext.EquipmentInstanceRunes.RemoveRange(existingRunes.Where(rune => !requestedRunes.ContainsKey(rune.RuneId)));
            foreach (var runeSnapshot in snapshot.Runes)
            {
                if (!existingRunesById.TryGetValue(runeSnapshot.RuneId, out var rune))
                {
                    rune = new EquipmentInstanceRuneEntity
                    {
                        RuneId = runeSnapshot.RuneId,
                        EquipmentInstanceId = entity.EquipmentInstanceId,
                        CreatedAt = now,
                        CreatedBy = request.UpdatedBy,
                    };
                    await dbContext.EquipmentInstanceRunes.AddAsync(rune);
                }
                rune.SlotIndex = runeSnapshot.SlotIndex;
                rune.ItemId = runeSnapshot.ItemId.Trim();
                rune.UpdatedAt = now;
                rune.UpdatedBy = request.UpdatedBy;
            }
        }
        return byId;
    }

    private async Task<bool> ApplyLoadoutsAsync(
        PlayerStateSnapshotSaveRequest request,
        DateTime now)
    {
        var ids = request.Loadouts.Select(loadout => loadout.EquipmentLoadoutId).ToArray();
        if (ids.Distinct().Count() != ids.Length)
            return false;
        var loadouts = await dbContext.EquipmentLoadouts.Where(loadout => ids.Contains(loadout.EquipmentLoadoutId)).ToListAsync();
        if (loadouts.Count != ids.Length || loadouts.Any(loadout => loadout.AccountId != request.AccountId || loadout.IsDeleted))
            return false;

        foreach (var snapshot in request.Loadouts)
        {
            var loadout = loadouts.Single(entity => entity.EquipmentLoadoutId == snapshot.EquipmentLoadoutId);
            if (loadout.UpdatedAt != snapshot.ExpectedUpdatedAt
                || snapshot.Slots.Any(slot => slot.EquipmentInstanceId == Guid.Empty || slot.SlotIndex < 0 || string.IsNullOrWhiteSpace(slot.SlotType))
                || snapshot.Slots.GroupBy(slot => $"{slot.SlotType.Trim().ToUpperInvariant()}\u001f{slot.SlotIndex}").Any(group => group.Count() > 1)
                || snapshot.Slots.GroupBy(slot => slot.EquipmentInstanceId).Any(group => group.Count() > 1))
                return false;

            var equipmentIds = snapshot.Slots.Select(slot => slot.EquipmentInstanceId).ToArray();
            var ownedEquipmentCount = await dbContext.EquipmentInstances.AsNoTracking()
                .CountAsync(equipment => equipmentIds.Contains(equipment.EquipmentInstanceId)
                    && equipment.AccountId == request.AccountId && !equipment.IsDeleted);
            if (ownedEquipmentCount != equipmentIds.Length)
                return false;
            foreach (var equipmentId in equipmentIds.Distinct())
            {
                if (await MarketListingRangeLock.HasActiveOrSuspendedAsync(dbContext, "EQUIPMENT", equipmentId))
                    return false;
            }

            var existingSlots = await dbContext.EquipmentLoadoutSlots
                .Where(slot => slot.EquipmentLoadoutId == loadout.EquipmentLoadoutId && !slot.IsDeleted)
                .ToListAsync();
            foreach (var slot in existingSlots)
            {
                slot.IsDeleted = true;
                slot.UpdatedAt = now;
                slot.UpdatedBy = request.UpdatedBy;
            }
            foreach (var slotSnapshot in snapshot.Slots)
            {
                var slot = existingSlots.FirstOrDefault(existing =>
                    string.Equals(existing.SlotType, slotSnapshot.SlotType.Trim(), StringComparison.OrdinalIgnoreCase)
                    && existing.SlotIndex == slotSnapshot.SlotIndex)
                    ?? new EquipmentLoadoutSlotEntity
                    {
                        EquipmentLoadoutSlotId = Guid.NewGuid(),
                        EquipmentLoadoutId = loadout.EquipmentLoadoutId,
                        CreatedAt = now,
                        CreatedBy = request.UpdatedBy,
                    };
                if (slot.EquipmentLoadoutSlotId != Guid.Empty && !existingSlots.Contains(slot))
                    await dbContext.EquipmentLoadoutSlots.AddAsync(slot);
                slot.SlotType = slotSnapshot.SlotType.Trim();
                slot.SlotIndex = slotSnapshot.SlotIndex;
                slot.EquipmentInstanceId = slotSnapshot.EquipmentInstanceId;
                slot.IsDeleted = false;
                slot.UpdatedAt = now;
                slot.UpdatedBy = request.UpdatedBy;
            }
            loadout.UpdatedAt = now;
            loadout.UpdatedBy = request.UpdatedBy;
        }
        return true;
    }

    private async Task<PlayerStateSnapshotAck> ReadCoreAckAsync(
        PlayerStateSnapshotSaveRequest request,
        PlayerStateSnapshotAck baseAck,
        DateTime fallbackUpdatedAt)
    {
        var entryIds = request.Inventories.SelectMany(inventory => inventory.ExpectedEntries.Select(entry => entry.InventoryEntryId)
                .Concat(inventory.Entries.Select(entry => entry.InventoryEntryId)))
            .Distinct().ToArray();
        var entries = await dbContext.InventoryEntries.AsNoTracking()
            .Where(entry => entryIds.Contains(entry.InventoryEntryId))
            .Select(entry => new PlayerStateInventoryEntryAck
            {
                InventoryEntryId = entry.InventoryEntryId,
                UpdatedAt = entry.UpdatedAt,
                IsDeleted = entry.IsDeleted,
            }).ToListAsync();
        var inventoryIds = request.Inventories.Select(inventory => inventory.InventoryId).ToArray();
        var inventories = await dbContext.Inventories.AsNoTracking()
            .Where(inventory => inventoryIds.Contains(inventory.InventoryId))
            .Select(inventory => new PlayerStateInventoryAck { InventoryId = inventory.InventoryId, UpdatedAt = inventory.UpdatedAt })
            .ToListAsync();
        var loadoutIds = request.Loadouts.Select(loadout => loadout.EquipmentLoadoutId).ToArray();
        var loadouts = await dbContext.EquipmentLoadouts.AsNoTracking()
            .Where(loadout => loadoutIds.Contains(loadout.EquipmentLoadoutId))
            .Select(loadout => new PlayerStateLoadoutAck { EquipmentLoadoutId = loadout.EquipmentLoadoutId, UpdatedAt = loadout.UpdatedAt })
            .ToListAsync();
        var equipmentIds = request.Equipment.Select(equipment => equipment.EquipmentInstanceId).ToArray();
        var equipment = await dbContext.EquipmentInstances.AsNoTracking()
            .Where(entity => equipmentIds.Contains(entity.EquipmentInstanceId))
            .Select(entity => new PlayerStateEquipmentAck { EquipmentInstanceId = entity.EquipmentInstanceId, UpdatedAt = entity.UpdatedAt })
            .ToListAsync();
        return new PlayerStateSnapshotAck
        {
            SnapshotId = baseAck.SnapshotId,
            AccountId = baseAck.AccountId,
            Entries = entries.OrderBy(entry => entry.InventoryEntryId).ToArray(),
            Inventories = inventories.OrderBy(inventory => inventory.InventoryId).ToArray(),
            Loadouts = loadouts.OrderBy(loadout => loadout.EquipmentLoadoutId).ToArray(),
            Equipment = equipment.OrderBy(entity => entity.EquipmentInstanceId).ToArray(),
            LearnedSkills = baseAck.LearnedSkills,
            SkillBindPresets = baseAck.SkillBindPresets,
            SkillTree = baseAck.SkillTree,
            AccountProgress = baseAck.AccountProgress,
        };
    }

    private async Task<PlayerStateSnapshotSaveResult> ApplySectionsAsync(
        PlayerStateSnapshotSaveRequest request,
        AccountEntity account,
        PlayerStateSnapshotAck baseAck,
        DateTime now)
    {
        JsonElement? learnedSkillsAck = null;
        JsonElement? bindPresetsAck = null;
        JsonElement? skillTreeAck = null;
        JsonElement? accountProgressAck = null;

        if (request.LearnedSkills.HasValue)
        {
            var section = TryDeserializeSection<PlayerStateLearnedSkillsSection>(request.LearnedSkills.Value);
            if (section is null || section.AccountId != request.AccountId)
                return Failure(PlayerStateSnapshotSaveFailure.Invalid, "learnedSkills section is invalid.");
            var applied = await ApplyLearnedSkillsAsync(section, request, now);
            if (applied is null)
                return Failure(PlayerStateSnapshotSaveFailure.Conflict, "learnedSkills section conflicts with current state.");
            learnedSkillsAck = JsonSerializer.SerializeToElement(applied, JsonOptions);
        }

        if (request.SkillBindPresets.HasValue)
        {
            var section = TryDeserializeSection<PlayerStateSkillBindPresetsSection>(request.SkillBindPresets.Value);
            if (section is null || section.AccountId != request.AccountId)
                return Failure(PlayerStateSnapshotSaveFailure.Invalid, "skillBindPresets section is invalid.");
            var applied = await ApplySkillBindPresetsAsync(section, request, now);
            if (applied is null)
                return Failure(PlayerStateSnapshotSaveFailure.Conflict, "skillBindPresets section conflicts with current state.");
            bindPresetsAck = JsonSerializer.SerializeToElement(applied, JsonOptions);
        }

        if (request.SkillTree.HasValue)
        {
            var section = TryDeserializeSection<PlayerStateSkillTreeSection>(request.SkillTree.Value);
            if (section is null || section.AccountId != request.AccountId)
                return Failure(PlayerStateSnapshotSaveFailure.Invalid, "skillTree section is invalid.");
            var applied = await ApplySkillTreeAsync(section, request, now);
            if (applied is null)
                return Failure(PlayerStateSnapshotSaveFailure.Conflict, "skillTree section conflicts with current state.");
            skillTreeAck = JsonSerializer.SerializeToElement(applied, JsonOptions);
        }

        if (request.AccountProgress.HasValue)
        {
            var section = TryDeserializeSection<PlayerStateAccountProgressSection>(request.AccountProgress.Value);
            if (section is null || section.AccountId != request.AccountId)
                return Failure(PlayerStateSnapshotSaveFailure.Invalid, "accountProgress section is invalid.");
            var applied = await ApplyAccountProgressAsync(section, account, request, now);
            if (applied is null)
                return Failure(PlayerStateSnapshotSaveFailure.Conflict, "accountProgress section conflicts with current state.");
            accountProgressAck = JsonSerializer.SerializeToElement(applied, JsonOptions);
        }

        return Success(new PlayerStateSnapshotAck
        {
            SnapshotId = baseAck.SnapshotId,
            AccountId = baseAck.AccountId,
            LearnedSkills = learnedSkillsAck,
            SkillBindPresets = bindPresetsAck,
            SkillTree = skillTreeAck,
            AccountProgress = accountProgressAck,
        });
    }

    private async Task<object?> ApplyLearnedSkillsAsync(
        PlayerStateLearnedSkillsSection section,
        PlayerStateSnapshotSaveRequest request,
        DateTime now)
    {
        if (section.Skills.Any(skill => skill.LearnedSkillId == Guid.Empty || skill.Level < 1 || string.IsNullOrWhiteSpace(skill.SkillId))
            || section.Skills.GroupBy(skill => skill.LearnedSkillId).Any(group => group.Count() > 1)
            || section.DeletedSkills.Any(skill => skill.LearnedSkillId == Guid.Empty || skill.ExpectedVersion < 1)
            || section.DeletedSkills.GroupBy(skill => skill.LearnedSkillId).Any(group => group.Count() > 1)
            || section.Skills.Select(skill => skill.LearnedSkillId).Intersect(section.DeletedSkills.Select(skill => skill.LearnedSkillId)).Any())
            return null;

        var existing = await dbContext.AccountLearnedSkills.Include(skill => skill.Sigils)
            .Where(skill => skill.AccountId == request.AccountId && !skill.IsDeleted).ToListAsync();
        var byId = existing.ToDictionary(skill => skill.LearnedSkillId);
        var requestedIds = section.Skills.Select(skill => skill.LearnedSkillId).ToHashSet();
        var deletedById = section.DeletedSkills.ToDictionary(skill => skill.LearnedSkillId);
        if (existing.Any(skill => !requestedIds.Contains(skill.LearnedSkillId) && !deletedById.ContainsKey(skill.LearnedSkillId)))
            return null;
        var deletedIds = new List<Guid>();
        foreach (var removed in existing.Where(skill => deletedById.ContainsKey(skill.LearnedSkillId)))
        {
            if (removed.Version != deletedById[removed.LearnedSkillId].ExpectedVersion)
                return null;
            removed.IsDeleted = true;
            removed.Version = Math.Max(1, removed.Version + 1);
            removed.UpdatedAt = now;
            removed.UpdatedBy = request.UpdatedBy;
            foreach (var sigil in removed.Sigils.Where(sigil => !sigil.IsDeleted))
            {
                sigil.IsDeleted = true;
                sigil.UpdatedAt = now;
                sigil.UpdatedBy = request.UpdatedBy;
            }
            deletedIds.Add(removed.LearnedSkillId);
        }

        var acknowledgements = new List<object>();
        foreach (var snapshot in section.Skills)
        {
            if (!IsValidLearnedSkill(snapshot)) return null;
            AccountLearnedSkillEntity entity;
            if (byId.TryGetValue(snapshot.LearnedSkillId, out var current))
            {
                if (!snapshot.ExpectedVersion.HasValue || current.Version != snapshot.ExpectedVersion.Value
                    || !string.Equals(current.SkillId, snapshot.SkillId.Trim(), StringComparison.OrdinalIgnoreCase))
                    return null;
                entity = current;
                entity.Level = snapshot.Level;
                entity.Version = Math.Max(1, entity.Version + 1);
                entity.UpdatedAt = now;
                entity.UpdatedBy = request.UpdatedBy;
            }
            else
            {
                if (snapshot.ExpectedVersion.HasValue) return null;
                entity = new AccountLearnedSkillEntity
                {
                    LearnedSkillId = snapshot.LearnedSkillId,
                    AccountId = request.AccountId,
                    SkillId = snapshot.SkillId.Trim(),
                    Level = snapshot.Level,
                    Version = 1,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = request.UpdatedBy,
                    UpdatedBy = request.UpdatedBy,
                    IsDeleted = false,
                };
                await dbContext.AccountLearnedSkills.AddAsync(entity);
            }

            var currentSigils = entity.Sigils.Where(sigil => !sigil.IsDeleted).ToDictionary(sigil => sigil.LearnedSkillSigilId);
            var requestedSigilIds = snapshot.Sigils.Select(sigil => sigil.LearnedSkillSigilId).ToHashSet();
            foreach (var removed in currentSigils.Values.Where(sigil => !requestedSigilIds.Contains(sigil.LearnedSkillSigilId)))
            {
                removed.IsDeleted = true;
                removed.UpdatedAt = now;
                removed.UpdatedBy = request.UpdatedBy;
            }
            foreach (var sigilSnapshot in snapshot.Sigils)
            {
                if (currentSigils.TryGetValue(sigilSnapshot.LearnedSkillSigilId, out var sigil))
                {
                    sigil.SigilId = sigilSnapshot.SigilId.Trim();
                    sigil.EquipGroupId = sigilSnapshot.EquipGroupId.Trim();
                    sigil.SlotIndex = sigilSnapshot.SlotIndex;
                    sigil.IsDeleted = false;
                    sigil.UpdatedAt = now;
                    sigil.UpdatedBy = request.UpdatedBy;
                }
                else
                {
                    sigil = new AccountLearnedSkillSigilEntity
                    {
                        LearnedSkillSigilId = sigilSnapshot.LearnedSkillSigilId,
                        LearnedSkillId = entity.LearnedSkillId,
                        SigilId = sigilSnapshot.SigilId.Trim(),
                        EquipGroupId = sigilSnapshot.EquipGroupId.Trim(),
                        SlotIndex = sigilSnapshot.SlotIndex,
                        CreatedAt = now,
                        UpdatedAt = now,
                        CreatedBy = request.UpdatedBy,
                        UpdatedBy = request.UpdatedBy,
                        IsDeleted = false,
                    };
                    await dbContext.AccountLearnedSkillSigils.AddAsync(sigil);
                }
            }
            acknowledgements.Add(new { learnedSkillId = entity.LearnedSkillId, version = entity.Version, updatedAt = entity.UpdatedAt, isDeleted = false });
        }
        return new { clientRevision = section.ClientRevision, entries = acknowledgements, deletedIds };
    }

    private async Task<object?> ApplySkillBindPresetsAsync(
        PlayerStateSkillBindPresetsSection section,
        PlayerStateSnapshotSaveRequest request,
        DateTime now)
    {
        if (section.SelectedPresetIndex is < 1 or > SkillBindPresetRepository.PresetCount
            || section.Presets.Count != SkillBindPresetRepository.PresetCount
            || section.Presets.Any(preset => preset.PresetIndex is < 1 or > SkillBindPresetRepository.PresetCount
                || preset.ActiveSkillSlots.Count > SkillBindPresetRepository.ActionRingSlotCount
                || preset.PassiveSkillSlots.Count > SkillBindPresetRepository.PassiveSlotCount)
            || section.Presets.GroupBy(preset => preset.PresetIndex).Any(group => group.Count() > 1))
            return null;

        var ownedIds = await dbContext.AccountLearnedSkills.AsNoTracking()
            .Where(skill => skill.AccountId == request.AccountId && !skill.IsDeleted)
            .Select(skill => skill.LearnedSkillId.ToString()).ToHashSetAsync(StringComparer.OrdinalIgnoreCase);
        bool IsOwned(string? value) => string.IsNullOrWhiteSpace(value)
            || string.Equals(value, SkillBindPresetRepository.WeaponNormalAttackBindingId, StringComparison.Ordinal)
            || ownedIds.Contains(value);
        if (section.Presets.Any(preset => !preset.ActiveSkillSlots.All(IsOwned)
            || !preset.PassiveSkillSlots.All(IsOwned) || !IsOwned(preset.LeftClickSkillId)))
            return null;

        var existing = await dbContext.SkillBindPresets
            .Where(preset => preset.AccountId == request.AccountId && !preset.IsDeleted).ToListAsync();
        var byIndex = existing.ToDictionary(preset => preset.PresetIndex);
        var acknowledgements = new List<object>();
        foreach (var snapshot in section.Presets.OrderBy(preset => preset.PresetIndex))
        {
            SkillBindPresetEntity entity;
            if (byIndex.TryGetValue(snapshot.PresetIndex, out var current))
            {
                if (!snapshot.ExpectedVersion.HasValue || current.Version != snapshot.ExpectedVersion.Value) return null;
                entity = current;
                entity.Version = Math.Max(1, entity.Version + 1);
            }
            else
            {
                if (snapshot.ExpectedVersion.HasValue) return null;
                entity = new SkillBindPresetEntity
                {
                    SkillBindPresetId = Guid.NewGuid(), AccountId = request.AccountId, PresetIndex = snapshot.PresetIndex,
                    Version = 1, CreatedAt = now, CreatedBy = request.UpdatedBy, IsDeleted = false,
                };
                await dbContext.SkillBindPresets.AddAsync(entity);
            }
            entity.ActiveSkillSlotsJson = JsonSerializer.Serialize(NormalizeSlots(snapshot.ActiveSkillSlots, SkillBindPresetRepository.ActionRingSlotCount));
            entity.LeftClickSkillId = string.IsNullOrWhiteSpace(snapshot.LeftClickSkillId) ? string.Empty : snapshot.LeftClickSkillId;
            entity.PassiveSkillSlotsJson = JsonSerializer.Serialize(NormalizeSlots(snapshot.PassiveSkillSlots, SkillBindPresetRepository.PassiveSlotCount));
            entity.IsUnlocked = entity.IsUnlocked || snapshot.PresetIndex <= 3;
            entity.IsSelected = snapshot.PresetIndex == section.SelectedPresetIndex;
            entity.UpdatedAt = now;
            entity.UpdatedBy = request.UpdatedBy;
            acknowledgements.Add(new { presetIndex = entity.PresetIndex, version = entity.Version, updatedAt = entity.UpdatedAt });
        }
        return new { clientRevision = section.ClientRevision, entries = acknowledgements };
    }

    private async Task<object?> ApplySkillTreeAsync(
        PlayerStateSkillTreeSection section,
        PlayerStateSnapshotSaveRequest request,
        DateTime now)
    {
        if (section.UnlockedNodes.Any(node => string.IsNullOrWhiteSpace(node.NodeId))
            || section.UnlockedNodes.GroupBy(node => node.NodeId.Trim(), StringComparer.Ordinal).Any(group => group.Count() > 1))
            return null;
        var state = await dbContext.AccountSkillTreeStates
            .FirstOrDefaultAsync(entity => entity.AccountId == request.AccountId && !entity.IsDeleted);
        if (state is null)
        {
            if (section.ExpectedVersion is not null and not 0) return null;
            state = new AccountSkillTreeStateEntity
            {
                AccountSkillTreeStateId = Guid.NewGuid(), AccountId = request.AccountId, Version = 1,
                CreatedAt = now, UpdatedAt = now, CreatedBy = request.UpdatedBy, UpdatedBy = request.UpdatedBy, IsDeleted = false,
            };
            await dbContext.AccountSkillTreeStates.AddAsync(state);
        }
        else
        {
            if (!section.ExpectedVersion.HasValue || state.Version != section.ExpectedVersion.Value) return null;
            state.Version = Math.Max(1, state.Version + 1);
            state.UpdatedAt = now;
            state.UpdatedBy = request.UpdatedBy;
            var existingNodes = await dbContext.AccountSkillTreeUnlockedNodes
                .Where(node => node.AccountSkillTreeStateId == state.AccountSkillTreeStateId).ToListAsync();
            dbContext.AccountSkillTreeUnlockedNodes.RemoveRange(existingNodes);
        }
        await dbContext.AccountSkillTreeUnlockedNodes.AddRangeAsync(section.UnlockedNodes.Select(node => new AccountSkillTreeUnlockedNodeEntity
        {
            AccountSkillTreeUnlockedNodeId = Guid.NewGuid(), AccountSkillTreeStateId = state.AccountSkillTreeStateId,
            NodeId = node.NodeId.Trim(), ConsumedClassId = string.IsNullOrWhiteSpace(node.ConsumedClassId) ? null : node.ConsumedClassId.Trim(),
            CreatedAt = now, UpdatedAt = now, CreatedBy = request.UpdatedBy, UpdatedBy = request.UpdatedBy,
        }));
        return new { clientRevision = section.ClientRevision, version = state.Version, updatedAt = state.UpdatedAt };
    }

    private async Task<object?> ApplyAccountProgressAsync(
        PlayerStateAccountProgressSection section,
        AccountEntity account,
        PlayerStateSnapshotSaveRequest request,
        DateTime now)
    {
        if (account.ProgressVersion != section.ExpectedProgressVersion || section.Level < 1 || section.TotalExperience < 0
            || section.ClassLevel < 1 || section.ClassExperience < 0 || string.IsNullOrWhiteSpace(section.ClassId)
            || (section.Mode.HasValue && section.Mode is not (0 or 2))
            || section.ClassProgresses.Any(progress => string.IsNullOrWhiteSpace(progress.ClassId) || progress.Level < 1 || progress.Experience < 0)
            || section.ClassProgresses.GroupBy(progress => progress.ClassId.Trim(), StringComparer.OrdinalIgnoreCase).Any(group => group.Count() > 1))
            return null;
        account.Level = section.Level;
        account.TotalExperience = section.TotalExperience;
        account.ClassId = section.ClassId.Trim();
        account.ClassLevel = section.ClassLevel;
        account.ClassExperience = section.ClassExperience;
        if (section.Mode.HasValue)
            account.Mode = section.Mode.Value;
        account.ProgressVersion = Math.Max(1, account.ProgressVersion + 1);
        account.UpdatedAt = now;
        account.UpdatedBy = request.UpdatedBy;
        var existing = await dbContext.AccountClassProgresses.Where(progress => progress.AccountId == request.AccountId).ToListAsync();
        var byClass = existing.ToDictionary(progress => progress.ClassId, StringComparer.OrdinalIgnoreCase);
        foreach (var progress in section.ClassProgresses)
        {
            if (!byClass.TryGetValue(progress.ClassId.Trim(), out var entity))
            {
                entity = new AccountClassProgressEntity { AccountId = request.AccountId, ClassId = progress.ClassId.Trim() };
                await dbContext.AccountClassProgresses.AddAsync(entity);
            }
            entity.Level = progress.Level;
            entity.Experience = progress.Experience;
            entity.UpdatedAt = now;
            entity.UpdatedBy = request.UpdatedBy;
        }
        return new { clientRevision = section.ClientRevision, progressVersion = account.ProgressVersion, updatedAt = account.UpdatedAt };
    }

    private async Task<PlayerStateSnapshotEntity?> FindSnapshotForUpdateAsync(Guid snapshotId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.PlayerStateSnapshots.FromSqlInterpolated($"""
                SELECT * FROM [dbo].[player_state_snapshot] WITH (UPDLOCK, HOLDLOCK)
                WHERE [snapshot_id] = {snapshotId}
                """).SingleOrDefaultAsync();
        }
        return await dbContext.PlayerStateSnapshots.SingleOrDefaultAsync(snapshot => snapshot.SnapshotId == snapshotId);
    }

    private async Task<AccountEntity?> FindAccountForUpdateAsync(Guid accountId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.Accounts.FromSqlInterpolated($"""
                SELECT * FROM [dbo].[account] WITH (UPDLOCK, HOLDLOCK)
                WHERE [uuid] = {accountId} AND [is_deleted] = 0
                """).SingleOrDefaultAsync();
        }
        return await dbContext.Accounts.SingleOrDefaultAsync(account => account.Uuid == accountId && !account.IsDeleted);
    }

    private async Task<IReadOnlyList<InventoryEntity>> FindAccountInventoriesForUpdateAsync(Guid accountId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.Inventories.FromSqlInterpolated($"""
                SELECT * FROM [dbo].[inventory] WITH (UPDLOCK, HOLDLOCK)
                WHERE [account_id] = {accountId} AND [is_deleted] = 0
                ORDER BY [inventory_id]
                """).ToListAsync();
        }
        return await dbContext.Inventories.Where(inventory => inventory.AccountId == accountId && !inventory.IsDeleted)
            .OrderBy(inventory => inventory.InventoryId).ToListAsync();
    }

    private async Task<IReadOnlyList<InventoryEntryEntity>> FindAccountEntriesForUpdateAsync(Guid accountId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.InventoryEntries.FromSqlInterpolated($"""
                SELECT entry.* FROM [dbo].[inventory_entry] entry WITH (UPDLOCK, HOLDLOCK)
                INNER JOIN [dbo].[inventory] inventory WITH (HOLDLOCK) ON inventory.[inventory_id] = entry.[inventory_id]
                WHERE inventory.[account_id] = {accountId}
                ORDER BY entry.[inventory_entry_id]
                """).ToListAsync();
        }
        return await (from entry in dbContext.InventoryEntries
                      join inventory in dbContext.Inventories on entry.InventoryId equals inventory.InventoryId
                      where inventory.AccountId == accountId
                      orderby entry.InventoryEntryId
                      select entry).ToListAsync();
    }

    private async Task<string?> ResolveEntryItemIdAsync(PlayerStateInventoryEntrySnapshot entry, Guid accountId)
    {
        if (string.IsNullOrWhiteSpace(entry.InstanceType) || !entry.InstanceId.HasValue)
            return entry.ItemId;
        if (!string.Equals(entry.InstanceType.Trim(), "EQUIPMENT", StringComparison.OrdinalIgnoreCase))
            return null;
        var authoritativeItemId = await dbContext.EquipmentInstances.AsNoTracking()
            .Where(instance => instance.EquipmentInstanceId == entry.InstanceId.Value
                && instance.AccountId == accountId && !instance.IsDeleted)
            .Select(instance => instance.ItemId).FirstOrDefaultAsync();
        return string.IsNullOrWhiteSpace(authoritativeItemId)
            || (!string.IsNullOrWhiteSpace(entry.ItemId)
                && !string.Equals(entry.ItemId, authoritativeItemId, StringComparison.OrdinalIgnoreCase))
            ? null
            : authoritativeItemId;
    }

    private static bool TryValidateRoot(PlayerStateSnapshotSaveRequest request, out string? detail)
    {
        if (request.SnapshotId == Guid.Empty || request.AccountId == Guid.Empty || request.UpdatedBy == Guid.Empty)
        {
            detail = "snapshotId, accountId and updatedBy are required.";
            return false;
        }
        if (request.Inventories.Select(inventory => inventory.InventoryId).Any(id => id == Guid.Empty)
            || request.Inventories.GroupBy(inventory => inventory.InventoryId).Any(group => group.Count() > 1))
        {
            detail = "inventoryId is invalid or duplicated.";
            return false;
        }
        if (request.Loadouts.Select(loadout => loadout.EquipmentLoadoutId).Any(id => id == Guid.Empty)
            || request.Equipment.Select(equipment => equipment.EquipmentInstanceId).Any(id => id == Guid.Empty))
        {
            detail = "loadout or equipment ID is invalid.";
            return false;
        }
        detail = null;
        return true;
    }

    private static bool IsValidEntry(PlayerStateInventoryEntrySnapshot entry)
        => entry.InventoryEntryId != Guid.Empty
            && entry.Quantity >= 1
            && (!entry.SlotIndex.HasValue || entry.SlotIndex >= 0)
            && !string.IsNullOrWhiteSpace(entry.ItemCategory)
            && ((string.IsNullOrWhiteSpace(entry.InstanceType) && !entry.InstanceId.HasValue && !string.IsNullOrWhiteSpace(entry.ItemId))
                || (!string.IsNullOrWhiteSpace(entry.InstanceType) && entry.InstanceId.HasValue));

    private static bool IsValidEquipment(PlayerStateEquipmentSnapshot snapshot)
        => snapshot.EnhanceLevel >= 0
            && snapshot.RuneMaxSlots >= 0
            && snapshot.TranscendenceRank >= 0
            && ((snapshot.DurabilityMax is null && snapshot.DurabilityValue is null)
                || (snapshot.DurabilityMax is int durabilityMax && durabilityMax > 0
                    && snapshot.DurabilityValue is int durabilityValue && durabilityValue >= 0 && durabilityValue <= durabilityMax))
            && snapshot.Enchants.All(enchant => enchant.EnchantId != Guid.Empty && enchant.SlotIndex >= 0
                && !string.IsNullOrWhiteSpace(enchant.EnchantMasterId) && !string.IsNullOrWhiteSpace(enchant.EffectId)
                && !string.IsNullOrWhiteSpace(enchant.Status) && !string.IsNullOrWhiteSpace(enchant.Type))
            && snapshot.Enchants.GroupBy(enchant => enchant.EnchantId).All(group => group.Count() == 1)
            && snapshot.Enchants.GroupBy(enchant => enchant.SlotIndex).All(group => group.Count() == 1)
            && snapshot.Enchants.GroupBy(enchant => enchant.EffectId, StringComparer.OrdinalIgnoreCase).All(group => group.Count() == 1)
            && snapshot.Runes.All(rune => rune.RuneId != Guid.Empty && rune.SlotIndex >= 0 && !string.IsNullOrWhiteSpace(rune.ItemId))
            && snapshot.Runes.GroupBy(rune => rune.RuneId).All(group => group.Count() == 1)
            && snapshot.Runes.GroupBy(rune => rune.SlotIndex).All(group => group.Count() == 1);

    private static bool IsJsonOrNull(string? value)
    {
        if (value is null) return true;
        try { using var _ = JsonDocument.Parse(value); return true; }
        catch (JsonException) { return false; }
    }

    private static T? TryDeserializeSection<T>(JsonElement element)
    {
        try { return element.Deserialize<T>(JsonOptions); }
        catch (JsonException) { return default; }
    }

    private static bool IsValidLearnedSkill(PlayerStateLearnedSkillSnapshot skill)
        => skill.LearnedSkillId != Guid.Empty
            && skill.Level >= 1
            && !string.IsNullOrWhiteSpace(skill.SkillId)
            && skill.Sigils.All(sigil => sigil.LearnedSkillSigilId != Guid.Empty
                && sigil.SlotIndex >= 0 && !string.IsNullOrWhiteSpace(sigil.SigilId)
                && !string.IsNullOrWhiteSpace(sigil.EquipGroupId))
            && skill.Sigils.GroupBy(sigil => sigil.LearnedSkillSigilId).All(group => group.Count() == 1)
            && skill.Sigils.GroupBy(sigil => sigil.SlotIndex).All(group => group.Count() == 1)
            && skill.Sigils.GroupBy(sigil => sigil.EquipGroupId, StringComparer.OrdinalIgnoreCase).All(group => group.Count() == 1);

    private static IReadOnlyList<string?> NormalizeSlots(IReadOnlyList<string?> values, int count)
        => Enumerable.Range(0, count)
            .Select(index => index < values.Count && !string.IsNullOrWhiteSpace(values[index]) ? values[index]!.Trim() : null)
            .ToArray();

    private static string ComputeRequestHash(PlayerStateSnapshotSaveRequest request)
        => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(request, JsonOptions)))).ToLowerInvariant();

    private static PlayerStateSnapshotSaveResult Success(PlayerStateSnapshotAck ack)
        => new(ack, PlayerStateSnapshotSaveFailure.None);

    private static PlayerStateSnapshotSaveResult Failure(PlayerStateSnapshotSaveFailure failure, string detail)
        => new(null, failure, detail);

    private static DateTime RoundToMilliseconds(DateTime value)
        => new(value.Ticks - value.Ticks % TimeSpan.TicksPerMillisecond, DateTimeKind.Utc);

    private static DateTime AdvanceUpdatedAt(DateTime current, DateTime candidate)
        => candidate > current.AddMilliseconds(1) ? candidate : current.AddMilliseconds(1);
}
