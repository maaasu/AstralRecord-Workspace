using System.Data;
using System.Globalization;
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
public sealed class PlayerStateSnapshotRepository(
    AstralRecordDbContext dbContext,
    MasterDataDbContext? masterDataDbContext = null) : IPlayerStateSnapshotRepository
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
                return replay is null || replay.SnapshotId != request.SnapshotId || replay.AccountId != request.AccountId
                    ? Failure(PlayerStateSnapshotSaveFailure.Conflict, "Stored snapshot acknowledgement is invalid.")
                    : Success(replay);
            }

            var account = await FindAccountForUpdateAsync(request.AccountId);
            if (account is null)
                return Failure(PlayerStateSnapshotSaveFailure.AccountNotFound, "Account was not found.");
            if (!await ChildIdsBelongToSnapshotParentsAsync(request))
                return Failure(PlayerStateSnapshotSaveFailure.Conflict, "Child ID belongs to another parent or deleted state.");

            var now = RoundToMilliseconds(DateTime.UtcNow);
            var accountInventories = request.Inventories.Count == 0
                ? Array.Empty<InventoryEntity>()
                : await FindAccountInventoriesForUpdateAsync(request.AccountId);
            var accountEntries = request.Inventories.Count == 0
                ? Array.Empty<InventoryEntryEntity>()
                : await FindActiveAccountEntriesForUpdateAsync(request.AccountId);
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

    public async Task<PlayerStateSnapshotAck?> FindCompletedAsync(Guid snapshotId, Guid accountId)
    {
        if (snapshotId == Guid.Empty || accountId == Guid.Empty)
            return null;

        var stored = await dbContext.PlayerStateSnapshots.AsNoTracking()
            .SingleOrDefaultAsync(snapshot => snapshot.SnapshotId == snapshotId && snapshot.AccountId == accountId);
        if (stored is null)
            return null;

        try
        {
            var acknowledgement = JsonSerializer.Deserialize<PlayerStateSnapshotAck>(stored.AckPayloadJson, JsonOptions);
            return acknowledgement is not null
                && acknowledgement.SnapshotId == snapshotId
                && acknowledgement.AccountId == accountId
                ? acknowledgement
                : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private async Task<PlayerStateSnapshotSaveResult> ApplyCoreStateAsync(
        PlayerStateSnapshotSaveRequest request,
        IReadOnlyList<InventoryEntity> accountInventories,
        IReadOnlyList<InventoryEntryEntity> accountEntries,
        DateTime now)
    {
        var newEquipmentIds = request.Equipment.Where(row => row.IsNew)
            .Select(row => row.EquipmentInstanceId).ToHashSet();
        var referencedEquipmentIds = request.Loadouts.SelectMany(row => row.Slots)
            .Select(row => row.EquipmentInstanceId).ToArray();
        var equipmentById = await PlayerStateUpdateLocks.EquipmentAsync(dbContext,
            request.Equipment.Select(row => row.EquipmentInstanceId).Concat(referencedEquipmentIds)
                .Concat(request.Inventories.SelectMany(row => row.Entries)
                    .Where(row => string.Equals(row.InstanceType?.Trim(), "EQUIPMENT", StringComparison.OrdinalIgnoreCase)
                        && row.InstanceId.HasValue).Select(row => row.InstanceId!.Value)),
            request.Equipment.Where(row => !row.IsNew).Select(row => row.EquipmentInstanceId)
                .Concat(referencedEquipmentIds.Where(id => !newEquipmentIds.Contains(id))));
        if (equipmentById is null)
            return Failure(PlayerStateSnapshotSaveFailure.Conflict, "Equipment is listed on the market.");

        var inventoriesById = accountInventories.ToDictionary(inventory => inventory.InventoryId);
        foreach (var snapshot in request.Inventories.Where(snapshot => snapshot.IsNew))
        {
            if (inventoriesById.ContainsKey(snapshot.InventoryId)
                || accountInventories.Any(inventory => string.Equals(inventory.InventoryType, snapshot.InventoryType!.Trim(), StringComparison.OrdinalIgnoreCase)
                    && string.Equals(inventory.InventoryProfile, snapshot.InventoryProfile!.Trim(), StringComparison.OrdinalIgnoreCase)))
                return Failure(PlayerStateSnapshotSaveFailure.Conflict, "New inventory conflicts with an existing inventory.");
            var inventory = new InventoryEntity
            {
                InventoryId = snapshot.InventoryId, AccountId = request.AccountId,
                InventoryType = snapshot.InventoryType!.Trim(), InventoryProfile = snapshot.InventoryProfile!.Trim(),
                SlotCapacity = snapshot.SlotCapacity, IsEnabled = snapshot.IsEnabled!.Value, MetadataJson = snapshot.MetadataJson,
                CreatedAt = now, UpdatedAt = now, CreatedBy = request.UpdatedBy, UpdatedBy = request.UpdatedBy,
                IsDeleted = false,
            };
            await dbContext.Inventories.AddAsync(inventory);
            inventoriesById.Add(inventory.InventoryId, inventory);
        }
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
            if (!UsesEntryDelta(inventorySnapshot))
            {
                var currentEntries = accountEntries.Where(entry => entry.InventoryId == inventory.InventoryId && !entry.IsDeleted)
                    .OrderBy(entry => entry.InventoryEntryId).ToArray();
                var expectedEntries = inventorySnapshot.ExpectedEntries.OrderBy(entry => entry.InventoryEntryId).ToArray();
                if (currentEntries.Length != expectedEntries.Length || currentEntries.Zip(expectedEntries).Any(pair =>
                    pair.First.InventoryEntryId != pair.Second.InventoryEntryId || pair.First.UpdatedAt != pair.Second.UpdatedAt))
                    return Failure(PlayerStateSnapshotSaveFailure.Conflict, "Inventory entry baseline snapshot is stale.");
            }
            else if (inventorySnapshot.ExpectedEntries.Any(expected => !entriesById.TryGetValue(expected.InventoryEntryId, out var entry)
                    || entry.InventoryId != inventory.InventoryId || entry.IsDeleted || entry.UpdatedAt != expected.UpdatedAt))
                return Failure(PlayerStateSnapshotSaveFailure.Conflict, "Inventory entry baseline snapshot is stale.");
            if (inventorySnapshot.MetadataDirty && !inventorySnapshot.IsNew)
            {
                if (!inventorySnapshot.ExpectedUpdatedAt.HasValue || inventory.UpdatedAt != inventorySnapshot.ExpectedUpdatedAt.Value)
                    return Failure(PlayerStateSnapshotSaveFailure.Conflict, "Inventory metadata snapshot is stale.");
                if (!IsJsonOrNull(inventorySnapshot.MetadataJson))
                    return Failure(PlayerStateSnapshotSaveFailure.Invalid, "Inventory metadataJson is invalid.");
                inventory.MetadataJson = inventorySnapshot.MetadataJson;
                inventory.UpdatedAt = AdvanceUpdatedAt(inventory.UpdatedAt, now);
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
            if (UsesEntryDelta(inventorySnapshot) && inventorySnapshot.DeletedEntryIds.Any(entryId => !expectedEntriesById.ContainsKey(entryId)
                    || request.Inventories.SelectMany(snapshot => snapshot.Entries)
                        .Any(entry => entry.InventoryEntryId == entryId)))
                return Failure(PlayerStateSnapshotSaveFailure.Invalid, "Deleted inventory entry is not an expected baseline row.");
        }

        equipmentById = request.Equipment.Count == 0
            ? equipmentById
            : await ApplyEquipmentAsync(request, now, equipmentById);
        if (equipmentById is null)
            return Failure(PlayerStateSnapshotSaveFailure.Conflict, "Equipment ownership or expectedUpdatedAt conflict.");

        var loadoutResult = request.Loadouts.Count == 0
            || await ApplyLoadoutsAsync(request, now, equipmentById);
        if (!loadoutResult)
            return Failure(PlayerStateSnapshotSaveFailure.Conflict, "Loadout ownership or expectedUpdatedAt conflict.");

        var requestedEntries = request.Inventories.SelectMany(snapshot => snapshot.Entries
            .Select(entry => (snapshot.InventoryId, Entry: entry))).ToArray();
        var entriesToDisable = (request.Inventories.Any(snapshot => !UsesEntryDelta(snapshot))
            ? accountEntries.Where(entry => !entry.IsDeleted && request.Inventories.Any(snapshot =>
                !UsesEntryDelta(snapshot) && snapshot.InventoryId == entry.InventoryId))
                .Concat(requestedEntries.Select(value => entriesById.GetValueOrDefault(value.Entry.InventoryEntryId)).OfType<InventoryEntryEntity>())
                .DistinctBy(entry => entry.InventoryEntryId).ToArray()
            : requestedEntries
            .Select(value => entriesById.GetValueOrDefault(value.Entry.InventoryEntryId))
            .OfType<InventoryEntryEntity>()
            .Where(entry => !entry.IsDeleted && requestedEntries.Any(value => value.Entry.InventoryEntryId == entry.InventoryEntryId
                && (entry.InventoryId != value.InventoryId || entry.SlotIndex != value.Entry.SlotIndex)))
            .DistinctBy(entry => entry.InventoryEntryId).ToArray())
            .Concat(request.Inventories.Where(UsesEntryDelta).SelectMany(snapshot => snapshot.DeletedEntryIds)
                .Select(entryId => entriesById[entryId]))
            .DistinctBy(entry => entry.InventoryEntryId).ToArray();
        foreach (var entry in entriesToDisable)
        {
            entry.IsDeleted = true;
            entry.UpdatedAt = AdvanceUpdatedAt(entry.UpdatedAt, now);
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

                var itemId = ResolveEntryItemId(entrySnapshot, request.AccountId, equipmentById);
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
                entry.UpdatedAt = AdvanceUpdatedAt(entry.UpdatedAt, now);
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
        DateTime now,
        Dictionary<Guid, EquipmentInstanceEntity> byId)
    {
        var ids = request.Equipment.Select(equipment => equipment.EquipmentInstanceId).ToArray();
        if (ids.Distinct().Count() != ids.Length)
            return null;

        foreach (var snapshot in request.Equipment)
        {
            if ((snapshot.IsNew && byId.ContainsKey(snapshot.EquipmentInstanceId))
                || (!snapshot.IsNew && (!byId.TryGetValue(snapshot.EquipmentInstanceId, out var current)
                    || current.AccountId != request.AccountId || current.IsDeleted)))
                return null;
        }

        foreach (var snapshot in request.Equipment)
        {
            EquipmentInstanceEntity entity;
            if (snapshot.IsNew)
            {
                entity = new EquipmentInstanceEntity
                {
                    EquipmentInstanceId = snapshot.EquipmentInstanceId,
                    AccountId = request.AccountId,
                    ItemId = snapshot.ItemId!.Trim(),
                    EnhanceLevel = snapshot.EnhanceLevel,
                    RuneMaxSlots = snapshot.RuneMaxSlots,
                    TranscendenceRank = snapshot.TranscendenceRank,
                    DurabilityMax = snapshot.DurabilityMax,
                    DurabilityValue = snapshot.DurabilityValue,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = request.UpdatedBy,
                    UpdatedBy = request.UpdatedBy,
                    IsDeleted = false,
                };
                await dbContext.EquipmentInstances.AddAsync(entity);
                byId.Add(entity.EquipmentInstanceId, entity);
                foreach (var statRollSnapshot in snapshot.StatRolls)
                {
                    await dbContext.EquipmentInstanceStatRolls.AddAsync(new EquipmentInstanceStatRollEntity
                    {
                        StatRollId = statRollSnapshot.StatRollId,
                        EquipmentInstanceId = entity.EquipmentInstanceId,
                        Status = statRollSnapshot.Status.Trim(),
                        RandomMin = statRollSnapshot.Min.Trim(),
                        RandomMax = statRollSnapshot.Max.Trim(),
                        SortOrder = statRollSnapshot.SortOrder,
                        CreatedAt = now,
                        UpdatedAt = now,
                        CreatedBy = request.UpdatedBy,
                        UpdatedBy = request.UpdatedBy,
                    });
                }
            }
            else
            {
                entity = byId[snapshot.EquipmentInstanceId];
                if (!snapshot.ExpectedUpdatedAt.HasValue || entity.UpdatedAt != snapshot.ExpectedUpdatedAt.Value)
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
                var existingRunes = await dbContext.EquipmentInstanceRunes
                    .Where(rune => rune.EquipmentInstanceId == entity.EquipmentInstanceId)
                    .ToListAsync();
                dbContext.EquipmentInstanceEnchants.RemoveRange(existingEnchants);
                dbContext.EquipmentInstanceRunes.RemoveRange(existingRunes);
                // Unique (slot/effect) constraints require physical child deletion to reach the database
                // before reusing an ID at another position in this full-state replacement.
                if (existingEnchants.Count > 0 || existingRunes.Count > 0)
                    await dbContext.SaveChangesAsync();
            }
            if (!IsValidEquipment(snapshot))
                return null;
            foreach (var enchantSnapshot in snapshot.Enchants)
            {
                var enchant = new EquipmentInstanceEnchantEntity
                {
                    EnchantId = enchantSnapshot.EnchantId,
                    EquipmentInstanceId = entity.EquipmentInstanceId,
                    SlotIndex = enchantSnapshot.SlotIndex,
                    EnchantMasterId = enchantSnapshot.EnchantMasterId.Trim(),
                    EffectId = enchantSnapshot.EffectId.Trim(),
                    Status = enchantSnapshot.Status.Trim(),
                    Type = enchantSnapshot.Type.Trim(),
                    Value = enchantSnapshot.Value,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = request.UpdatedBy,
                    UpdatedBy = request.UpdatedBy,
                };
                await dbContext.EquipmentInstanceEnchants.AddAsync(enchant);
            }
            foreach (var runeSnapshot in snapshot.Runes)
            {
                var rune = new EquipmentInstanceRuneEntity
                {
                    RuneId = runeSnapshot.RuneId,
                    EquipmentInstanceId = entity.EquipmentInstanceId,
                    SlotIndex = runeSnapshot.SlotIndex,
                    ItemId = runeSnapshot.ItemId.Trim(),
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = request.UpdatedBy,
                    UpdatedBy = request.UpdatedBy,
                };
                await dbContext.EquipmentInstanceRunes.AddAsync(rune);
            }
        }
        return byId;
    }

    private async Task<bool> ApplyLoadoutsAsync(
        PlayerStateSnapshotSaveRequest request,
        DateTime now,
        IReadOnlyDictionary<Guid, EquipmentInstanceEntity> equipmentById)
    {
        var ids = request.Loadouts.Select(loadout => loadout.EquipmentLoadoutId).ToArray();
        if (ids.Distinct().Count() != ids.Length)
            return false;
        var loadouts = (await FindAccountLoadoutsForUpdateAsync(request.AccountId)).ToList();
        if (loadouts.Any(loadout => loadout.AccountId != request.AccountId || loadout.IsDeleted)
            || request.Loadouts.Where(snapshot => !snapshot.IsNew).Any(snapshot => !loadouts.Any(loadout => loadout.EquipmentLoadoutId == snapshot.EquipmentLoadoutId)))
            return false;
        foreach (var snapshot in request.Loadouts.Where(snapshot => snapshot.IsNew))
        {
            if (loadouts.Any(loadout => loadout.EquipmentLoadoutId == snapshot.EquipmentLoadoutId)
                || loadouts.Any(loadout => string.Equals(loadout.LoadoutProfile, snapshot.LoadoutProfile!.Trim(), StringComparison.OrdinalIgnoreCase)
                    && (loadout.IsActive && snapshot.IsActive!.Value || string.Equals(loadout.LoadoutName, snapshot.LoadoutName!.Trim(), StringComparison.OrdinalIgnoreCase))))
                return false;
            var loadout = new EquipmentLoadoutEntity
            {
                EquipmentLoadoutId = snapshot.EquipmentLoadoutId, AccountId = request.AccountId,
                LoadoutProfile = snapshot.LoadoutProfile!.Trim(), LoadoutName = snapshot.LoadoutName!.Trim(),
                SortOrder = snapshot.SortOrder, IsActive = snapshot.IsActive!.Value, MetadataJson = snapshot.MetadataJson,
                CreatedAt = now, UpdatedAt = now, CreatedBy = request.UpdatedBy, UpdatedBy = request.UpdatedBy,
                IsDeleted = false,
            };
            await dbContext.EquipmentLoadouts.AddAsync(loadout);
            loadouts.Add(loadout);
        }

        foreach (var snapshot in request.Loadouts)
        {
            var loadout = loadouts.Single(entity => entity.EquipmentLoadoutId == snapshot.EquipmentLoadoutId);
            if ((!snapshot.IsNew && (!snapshot.ExpectedUpdatedAt.HasValue || loadout.UpdatedAt != snapshot.ExpectedUpdatedAt.Value))
                || snapshot.Slots.Any(slot => slot.EquipmentInstanceId == Guid.Empty || slot.SlotIndex < 0 || string.IsNullOrWhiteSpace(slot.SlotType))
                || snapshot.Slots.GroupBy(slot => $"{slot.SlotType.Trim().ToUpperInvariant()}\u001f{slot.SlotIndex}").Any(group => group.Count() > 1)
                || snapshot.Slots.GroupBy(slot => slot.EquipmentInstanceId).Any(group => group.Count() > 1))
                return false;

            var equipmentIds = snapshot.Slots.Select(slot => slot.EquipmentInstanceId).Distinct().ToArray();
            var pendingEquipmentIds = equipmentIds.Where(id =>
                    equipmentById.TryGetValue(id, out var equipment)
                    && equipment.AccountId == request.AccountId
                    && dbContext.Entry(equipment).State == EntityState.Added)
                .ToHashSet();
            var persistedEquipmentIds = equipmentIds.Where(id => !pendingEquipmentIds.Contains(id)).ToArray();
            var ownedEquipmentCount = persistedEquipmentIds.Count(id =>
                equipmentById.TryGetValue(id, out var equipment)
                && equipment.AccountId == request.AccountId && !equipment.IsDeleted);
            if (ownedEquipmentCount != persistedEquipmentIds.Length)
                return false;

            var existingSlots = await dbContext.EquipmentLoadoutSlots
                .Where(slot => slot.EquipmentLoadoutId == loadout.EquipmentLoadoutId && !slot.IsDeleted)
                .ToListAsync();
            var changedSlotKeys = snapshot.Slots.Select(slot => (slot.SlotType.Trim().ToUpperInvariant(), slot.SlotIndex))
                .Concat(snapshot.DeletedSlots.Select(slot => (slot.SlotType.Trim().ToUpperInvariant(), slot.SlotIndex))).ToHashSet();
            var slotsToDisable = UsesLoadoutSlotDelta(snapshot)
                ? existingSlots.Where(slot => changedSlotKeys.Contains((slot.SlotType.Trim().ToUpperInvariant(), slot.SlotIndex))).ToList()
                : existingSlots;
            foreach (var slot in slotsToDisable)
            {
                slot.IsDeleted = true;
                slot.UpdatedAt = AdvanceUpdatedAt(slot.UpdatedAt, now);
                slot.UpdatedBy = request.UpdatedBy;
            }
            // Filtered unique indexes only release the old positions after this flush. Without it,
            // a same-request slot/equipment swap can be ordered as conflicting UPDATE statements.
            if (slotsToDisable.Count > 0)
                await dbContext.SaveChangesAsync();
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
                slot.UpdatedAt = AdvanceUpdatedAt(slot.UpdatedAt, now);
                slot.UpdatedBy = request.UpdatedBy;
            }
            loadout.UpdatedAt = AdvanceUpdatedAt(loadout.UpdatedAt, now);
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
        var entries = entryIds.Length == 0
            ? []
            : await dbContext.InventoryEntries.AsNoTracking()
                .Where(entry => entryIds.Contains(entry.InventoryEntryId))
                .Select(entry => new PlayerStateInventoryEntryAck
                {
                    InventoryEntryId = entry.InventoryEntryId,
                    UpdatedAt = entry.UpdatedAt,
                    IsDeleted = entry.IsDeleted,
                }).ToListAsync();
        var inventoryIds = request.Inventories.Select(inventory => inventory.InventoryId).ToArray();
        var inventories = inventoryIds.Length == 0
            ? []
            : await dbContext.Inventories.AsNoTracking()
                .Where(inventory => inventoryIds.Contains(inventory.InventoryId))
                .Select(inventory => new PlayerStateInventoryAck { InventoryId = inventory.InventoryId, UpdatedAt = inventory.UpdatedAt })
                .ToListAsync();
        var loadoutIds = request.Loadouts.Select(loadout => loadout.EquipmentLoadoutId).ToArray();
        var loadouts = loadoutIds.Length == 0
            ? []
            : await dbContext.EquipmentLoadouts.AsNoTracking()
                .Where(loadout => loadoutIds.Contains(loadout.EquipmentLoadoutId))
                .Select(loadout => new PlayerStateLoadoutAck { EquipmentLoadoutId = loadout.EquipmentLoadoutId, UpdatedAt = loadout.UpdatedAt })
                .ToListAsync();
        var equipmentIds = request.Equipment.Select(equipment => equipment.EquipmentInstanceId).ToArray();
        var equipment = equipmentIds.Length == 0
            ? []
            : await dbContext.EquipmentInstances.AsNoTracking()
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
            Waystones = baseAck.Waystones,
            QuestState = baseAck.QuestState,
            LoginBonusClaims = baseAck.LoginBonusClaims,
            GuideProgress = baseAck.GuideProgress,
            AdventureRecords = baseAck.AdventureRecords,
            PlayerSettings = baseAck.PlayerSettings,
            MailClaim = baseAck.MailClaim,
            MailDelete = baseAck.MailDelete,
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
        JsonElement? waystonesAck = null;
        JsonElement? questStateAck = null;
        JsonElement? loginBonusClaimsAck = null;
        JsonElement? guideProgressAck = null;
        JsonElement? adventureRecordsAck = null;
        JsonElement? playerSettingsAck = null;
        JsonElement? mailClaimAck = null;
        JsonElement? mailDeleteAck = null;

        if (request.LearnedSkills.HasValue)
        {
            var section = TryDeserializeSection<PlayerStateLearnedSkillsSection>(request.LearnedSkills.Value);
            if (section is null || section.AccountId != request.AccountId)
                return Failure(PlayerStateSnapshotSaveFailure.Invalid, "learnedSkills section is invalid.");
            var applied = await ApplyLearnedSkillsAsync(section, request, now);
            if (applied is null)
                return Failure(PlayerStateSnapshotSaveFailure.Conflict, "learnedSkills section conflicts with current state.");
            learnedSkillsAck = JsonSerializer.SerializeToElement(applied, JsonOptions);
            // Bind ownership must see both additions and deletions from this same snapshot.
            await dbContext.SaveChangesAsync();
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

        if (request.Waystones.HasValue)
        {
            var section = TryDeserializeSection<PlayerStateWaystonesSection>(request.Waystones.Value);
            if (section is null)
                return Failure(PlayerStateSnapshotSaveFailure.Invalid, "waystones section is invalid.");
            var applied = await ApplyWaystonesAsync(section, request, now);
            if (applied is null)
                return Failure(PlayerStateSnapshotSaveFailure.Invalid, "waystones section contains an invalid or duplicated ID.");
            waystonesAck = JsonSerializer.SerializeToElement(applied, JsonOptions);
        }

        if (request.QuestState.HasValue)
        {
            var section = TryDeserializeSection<PlayerStateQuestStateSection>(request.QuestState.Value);
            if (section is null || section.AccountId != request.AccountId)
                return Failure(PlayerStateSnapshotSaveFailure.Invalid, "questState section is invalid.");
            var applied = await ApplyQuestStateAsync(section, request, now);
            if (applied is null)
                return Failure(PlayerStateSnapshotSaveFailure.Conflict, "questState section conflicts with current state.");
            questStateAck = JsonSerializer.SerializeToElement(applied, JsonOptions);
        }

        if (request.LoginBonusClaims.HasValue)
        {
            var section = TryDeserializeSection<PlayerStateLoginBonusClaimsSection>(request.LoginBonusClaims.Value);
            if (section is null)
                return Failure(PlayerStateSnapshotSaveFailure.Invalid, "loginBonusClaims section is invalid.");
            var applied = await ApplyLoginBonusClaimsAsync(section, request, now);
            if (applied is null)
                return Failure(PlayerStateSnapshotSaveFailure.Conflict, "loginBonusClaims section conflicts with current state.");
            loginBonusClaimsAck = JsonSerializer.SerializeToElement(applied, JsonOptions);
        }

        if (request.GuideProgress.HasValue)
        {
            var section = TryDeserializeSection<PlayerStateGuideProgressSection>(request.GuideProgress.Value);
            if (section is null || section.AccountId != request.AccountId)
                return Failure(PlayerStateSnapshotSaveFailure.Invalid, "guideProgress section is invalid.");
            var applied = await ApplyGuideProgressAsync(section, request, now);
            if (applied is null)
                return Failure(PlayerStateSnapshotSaveFailure.Conflict, "guideProgress section conflicts with current state.");
            guideProgressAck = JsonSerializer.SerializeToElement(applied, JsonOptions);
        }

        if (request.AdventureRecords.HasValue)
        {
            var section = TryDeserializeSection<PlayerStateAdventureRecordsSection>(request.AdventureRecords.Value);
            if (section is null || section.AccountId != request.AccountId)
                return Failure(PlayerStateSnapshotSaveFailure.Invalid, "adventureRecords section is invalid.");
            var applied = await ApplyAdventureRecordsAsync(section, request, now);
            if (applied is null)
                return Failure(PlayerStateSnapshotSaveFailure.Conflict, "adventureRecords section conflicts with current state.");
            adventureRecordsAck = JsonSerializer.SerializeToElement(applied, JsonOptions);
        }

        if (request.PlayerSettings.HasValue)
        {
            var section = TryDeserializeSection<PlayerStatePlayerSettingsSection>(request.PlayerSettings.Value);
            if (section is null || section.UserId != account.UserId)
                return Failure(PlayerStateSnapshotSaveFailure.Invalid, "playerSettings section is invalid.");
            var applied = await ApplyPlayerSettingsAsync(section, request, now);
            if (applied is null)
                return Failure(PlayerStateSnapshotSaveFailure.Conflict, "playerSettings section conflicts with current state.");
            playerSettingsAck = JsonSerializer.SerializeToElement(applied, JsonOptions);
        }

        if (request.MailClaim.HasValue)
        {
            var section = TryDeserializeSection<PlayerStateMailClaimSection>(request.MailClaim.Value);
            if (section is null || section.AccountId != request.AccountId)
                return Failure(PlayerStateSnapshotSaveFailure.Invalid, "mailClaim section is invalid.");
            var applied = await ApplyMailClaimAsync(section, account, request, now);
            if (applied is null)
                return Failure(PlayerStateSnapshotSaveFailure.Conflict, "mailClaim section conflicts with current state.");
            mailClaimAck = JsonSerializer.SerializeToElement(applied, JsonOptions);
        }

        if (request.MailDelete.HasValue)
        {
            var section = TryDeserializeSection<PlayerStateMailDeleteSection>(request.MailDelete.Value);
            if (section is null || section.AccountId != request.AccountId)
                return Failure(PlayerStateSnapshotSaveFailure.Invalid, "mailDelete section is invalid.");
            var applied = await ApplyMailDeleteAsync(section, account, request, now);
            if (applied is null)
                return Failure(PlayerStateSnapshotSaveFailure.Conflict, "mailDelete section conflicts with current state.");
            mailDeleteAck = JsonSerializer.SerializeToElement(applied, JsonOptions);
        }

        return Success(new PlayerStateSnapshotAck
        {
            SnapshotId = baseAck.SnapshotId,
            AccountId = baseAck.AccountId,
            LearnedSkills = learnedSkillsAck,
            SkillBindPresets = bindPresetsAck,
            SkillTree = skillTreeAck,
            AccountProgress = accountProgressAck,
            Waystones = waystonesAck,
            QuestState = questStateAck,
            LoginBonusClaims = loginBonusClaimsAck,
            GuideProgress = guideProgressAck,
            AdventureRecords = adventureRecordsAck,
            PlayerSettings = playerSettingsAck,
            MailClaim = mailClaimAck,
            MailDelete = mailDeleteAck,
        });
    }

    private async Task<object?> ApplyWaystonesAsync(
        PlayerStateWaystonesSection section,
        PlayerStateSnapshotSaveRequest request,
        DateTime now)
    {
        if (section.UnlockedWaystoneIds is null)
            return null;

        var normalizedWaystoneIds = new List<string>(section.UnlockedWaystoneIds.Count);
        foreach (var rawWaystoneId in section.UnlockedWaystoneIds)
        {
            if (string.IsNullOrWhiteSpace(rawWaystoneId)
                || rawWaystoneId.Length > 100
                || !string.Equals(rawWaystoneId, rawWaystoneId.Trim(), StringComparison.Ordinal))
                return null;
            normalizedWaystoneIds.Add(rawWaystoneId);
        }
        if (normalizedWaystoneIds.GroupBy(waystoneId => waystoneId, StringComparer.OrdinalIgnoreCase).Any(group => group.Count() > 1))
            return null;

        // Existing account-waystone API and plugin WaystoneDefinition use string IDs (ws-...).
        // Keep the existing case-insensitive no-op behavior without introducing a parallel UUID contract.
        var activeWaystoneIds = await dbContext.AccountWaystoneUnlocks.AsNoTracking()
            .Where(unlock => unlock.AccountId == request.AccountId && !unlock.IsDeleted)
            .Select(unlock => unlock.WaystoneId)
            .ToListAsync();
        var existing = activeWaystoneIds.ToHashSet(StringComparer.OrdinalIgnoreCase);
        foreach (var waystoneId in normalizedWaystoneIds.Where(waystoneId => !existing.Contains(waystoneId)))
        {
            await dbContext.AccountWaystoneUnlocks.AddAsync(new AccountWaystoneUnlockEntity
            {
                AccountWaystoneUnlockId = Guid.NewGuid(),
                AccountId = request.AccountId,
                WaystoneId = waystoneId,
                UnlockedAt = now,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = request.UpdatedBy,
                UpdatedBy = request.UpdatedBy,
                IsDeleted = false,
            });
        }
        return new
        {
            clientRevision = section.ClientRevision,
            unlockedWaystoneIds = normalizedWaystoneIds,
        };
    }

    private async Task<object?> ApplyQuestStateAsync(
        PlayerStateQuestStateSection section,
        PlayerStateSnapshotSaveRequest request,
        DateTime now)
    {
        var state = await dbContext.AccountQuestStates
            .Include(value => value.ActiveQuests)
                .ThenInclude(active => active.ObjectiveProgress)
            .Include(value => value.Completions)
            .Include(value => value.Cooldowns)
            .FirstOrDefaultAsync(value => value.AccountId == request.AccountId && !value.IsDeleted);

        if (state is null)
        {
            if (section.ExpectedVersion != 0)
                return null;
            state = new AccountQuestStateEntity
            {
                AccountQuestStateId = Guid.NewGuid(),
                AccountId = request.AccountId,
                Version = 1,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = request.UpdatedBy,
                UpdatedBy = request.UpdatedBy,
                IsDeleted = false,
            };
            await dbContext.AccountQuestStates.AddAsync(state);
        }
        else
        {
            if (state.Version != section.ExpectedVersion)
                return null;
            state.Version = Math.Max(1, state.Version + 1);
            state.UpdatedAt = now;
            state.UpdatedBy = request.UpdatedBy;
            dbContext.AccountQuestObjectiveProgresses.RemoveRange(
                state.ActiveQuests.SelectMany(active => active.ObjectiveProgress));
            dbContext.AccountQuestActives.RemoveRange(state.ActiveQuests);
            dbContext.AccountQuestCompletions.RemoveRange(state.Completions);
            dbContext.AccountQuestCooldowns.RemoveRange(state.Cooldowns);
            await dbContext.SaveChangesAsync();
            state.ActiveQuests.Clear();
            state.Completions.Clear();
            state.Cooldowns.Clear();
        }

        foreach (var snapshot in section.ActiveQuests)
        {
            var active = new AccountQuestActiveEntity
            {
                AccountQuestActiveId = Guid.NewGuid(),
                AccountQuestStateId = state.AccountQuestStateId,
                QuestId = snapshot.QuestId.Trim(),
                AcceptedAt = FromEpochMillis(snapshot.AcceptedAtEpochMillis),
                AcceptedNpcId = string.IsNullOrWhiteSpace(snapshot.AcceptedNpcId) ? null : snapshot.AcceptedNpcId.Trim(),
                ReadyToTurnIn = snapshot.ReadyToTurnIn,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = request.UpdatedBy,
                UpdatedBy = request.UpdatedBy,
            };
            foreach (var objectiveSnapshot in snapshot.ObjectiveProgress)
            {
                active.ObjectiveProgress.Add(new AccountQuestObjectiveProgressEntity
                {
                    AccountQuestObjectiveProgressId = Guid.NewGuid(),
                    AccountQuestActiveId = active.AccountQuestActiveId,
                    ObjectiveId = objectiveSnapshot.ObjectiveId.Trim(),
                    Progress = objectiveSnapshot.Progress,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = request.UpdatedBy,
                    UpdatedBy = request.UpdatedBy,
                });
            }
            state.ActiveQuests.Add(active);
            await dbContext.AccountQuestActives.AddAsync(active);
        }
        foreach (var snapshot in section.Completions)
        {
            var completion = new AccountQuestCompletionEntity
            {
                AccountQuestCompletionId = Guid.NewGuid(),
                AccountQuestStateId = state.AccountQuestStateId,
                QuestId = snapshot.QuestId.Trim(),
                CompletedAt = FromEpochMillis(snapshot.CompletedAtEpochMillis),
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = request.UpdatedBy,
                UpdatedBy = request.UpdatedBy,
            };
            state.Completions.Add(completion);
            await dbContext.AccountQuestCompletions.AddAsync(completion);
        }
        foreach (var snapshot in section.Cooldowns)
        {
            var cooldown = new AccountQuestCooldownEntity
            {
                AccountQuestCooldownId = Guid.NewGuid(),
                AccountQuestStateId = state.AccountQuestStateId,
                QuestId = snapshot.QuestId.Trim(),
                CooldownUntil = FromEpochMillis(snapshot.CooldownUntilEpochMillis),
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = request.UpdatedBy,
                UpdatedBy = request.UpdatedBy,
            };
            state.Cooldowns.Add(cooldown);
            await dbContext.AccountQuestCooldowns.AddAsync(cooldown);
        }
        return new { clientRevision = section.ClientRevision, version = state.Version, updatedAt = state.UpdatedAt };
    }

    private async Task<object?> ApplyLoginBonusClaimsAsync(
        PlayerStateLoginBonusClaimsSection section,
        PlayerStateSnapshotSaveRequest request,
        DateTime now)
    {
        var requestedDates = section.ClaimDates.ToHashSet();
        if (await dbContext.LoginBonusClaims.AnyAsync(claim =>
            claim.AccountId == request.AccountId && requestedDates.Contains(claim.ClaimDate) && !claim.IsDeleted))
            return null;

        var acknowledgements = new List<object>();
        foreach (var claimDate in section.ClaimDates.Order())
        {
            var claim = new LoginBonusClaimEntity
            {
                LoginBonusClaimId = Guid.NewGuid(),
                AccountId = request.AccountId,
                ClaimDate = claimDate,
                ClaimedAt = now,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = request.UpdatedBy,
                UpdatedBy = request.UpdatedBy,
                IsDeleted = false,
            };
            await dbContext.LoginBonusClaims.AddAsync(claim);
            acknowledgements.Add(new
            {
                claimDate,
                loginBonusClaimId = claim.LoginBonusClaimId,
                claimedAt = claim.ClaimedAt,
            });
        }
        return new { clientRevision = section.ClientRevision, claims = acknowledgements };
    }

    private async Task<object?> ApplyGuideProgressAsync(
        PlayerStateGuideProgressSection section,
        PlayerStateSnapshotSaveRequest request,
        DateTime now)
    {
        var existing = await dbContext.AccountGuideStepProgresses
            .Where(progress => progress.AccountId == request.AccountId)
            .ToListAsync();
        var existingKeys = existing.Select(progress => GuideStepKey(progress.GuideId, progress.StepId))
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        var requestedSteps = section.CompletedStepKeys
            .Select(step => (GuideId: step.GuideId.Trim(), StepId: step.StepId.Trim())).ToArray();
        var requestedKeys = requestedSteps.Select(step => GuideStepKey(step.GuideId, step.StepId))
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        if (section.IsFullSnapshot && existingKeys.Except(requestedKeys, StringComparer.OrdinalIgnoreCase).Any())
            return null;

        foreach (var (guideId, stepId) in requestedSteps
            .Where(step => !existingKeys.Contains(GuideStepKey(step.GuideId, step.StepId))))
        {
            await dbContext.AccountGuideStepProgresses.AddAsync(new AccountGuideStepProgressEntity
            {
                AccountGuideStepProgressId = Guid.NewGuid(), AccountId = request.AccountId,
                GuideId = guideId, StepId = stepId, CompletedAt = now, CreatedAt = now,
                CreatedBy = request.UpdatedBy,
            });
        }
        return new
        {
            clientRevision = section.ClientRevision,
            completedStepKeys = requestedSteps.OrderBy(key => key.GuideId).ThenBy(key => key.StepId)
                .Select(key => new { guideId = key.GuideId, stepId = key.StepId }),
        };
    }

    private async Task<object?> ApplyAdventureRecordsAsync(
        PlayerStateAdventureRecordsSection section,
        PlayerStateSnapshotSaveRequest request,
        DateTime now)
    {
        var mobIds = section.MobDefeatDeltas.Select(delta => delta.MobId.Trim()).ToArray();
        var mobs = await PlayerStateUpdateLocks.MobsAsync(dbContext, request.AccountId, mobIds);
        var mobsById = mobs.ToDictionary(record => record.MobId, StringComparer.OrdinalIgnoreCase);
        foreach (var delta in section.MobDefeatDeltas)
        {
            var mobId = delta.MobId.Trim();
            if (!mobsById.TryGetValue(mobId, out var record))
            {
                record = new AccountMobRecordEntity
                {
                    AccountMobRecordId = Guid.NewGuid(), AccountId = request.AccountId, MobId = mobId,
                    MobCategory = delta.MobCategory.Trim().ToUpperInvariant(), DefeatCount = delta.Delta,
                    FirstDefeatedAt = now, LastDefeatedAt = now, CreatedAt = now, UpdatedAt = now,
                    CreatedBy = request.UpdatedBy, UpdatedBy = request.UpdatedBy, IsDeleted = false,
                };
                await dbContext.AccountMobRecords.AddAsync(record);
                mobsById.Add(mobId, record);
            }
            else
            {
                try { record.DefeatCount = checked(record.DefeatCount + delta.Delta); }
                catch (OverflowException) { return null; }
                record.MobCategory = delta.MobCategory.Trim().ToUpperInvariant();
                record.LastDefeatedAt = now;
                record.UpdatedAt = now;
                record.UpdatedBy = request.UpdatedBy;
                record.IsDeleted = false;
            }
        }

        var dungeonIds = section.DungeonClearDeltas.Select(delta => delta.DungeonId.Trim()).ToArray();
        var dungeons = await PlayerStateUpdateLocks.DungeonsAsync(dbContext, request.AccountId, dungeonIds);
        var dungeonsById = dungeons.ToDictionary(record => record.DungeonId, StringComparer.OrdinalIgnoreCase);
        foreach (var delta in section.DungeonClearDeltas)
        {
            var dungeonId = delta.DungeonId.Trim();
            if (!dungeonsById.TryGetValue(dungeonId, out var record))
            {
                record = new AccountDungeonRecordEntity
                {
                    AccountDungeonRecordId = Guid.NewGuid(), AccountId = request.AccountId, DungeonId = dungeonId,
                    ClearCount = delta.Delta, FirstClearedAt = now, LastClearedAt = now, CreatedAt = now, UpdatedAt = now,
                    CreatedBy = request.UpdatedBy, UpdatedBy = request.UpdatedBy, IsDeleted = false,
                };
                await dbContext.AccountDungeonRecords.AddAsync(record);
                dungeonsById.Add(dungeonId, record);
            }
            else
            {
                try { record.ClearCount = checked(record.ClearCount + delta.Delta); }
                catch (OverflowException) { return null; }
                record.LastClearedAt = now;
                record.UpdatedAt = now;
                record.UpdatedBy = request.UpdatedBy;
                record.IsDeleted = false;
            }
        }
        return new
        {
            clientRevision = section.ClientRevision,
            mobDefeats = mobsById.Values.OrderBy(record => record.MobId).Select(record => new
            {
                mobId = record.MobId, mobCategory = record.MobCategory, defeatCount = record.DefeatCount,
                updatedAt = record.UpdatedAt,
            }),
            dungeonClears = dungeonsById.Values.OrderBy(record => record.DungeonId).Select(record => new
            {
                dungeonId = record.DungeonId, clearCount = record.ClearCount, updatedAt = record.UpdatedAt,
            }),
        };
    }

    private async Task<object?> ApplyPlayerSettingsAsync(
        PlayerStatePlayerSettingsSection section,
        PlayerStateSnapshotSaveRequest request,
        DateTime now)
    {
        var existing = await FindPlayerSettingsForUpdateAsync(section.UserId);
        var existingById = existing.ToDictionary(setting => setting.UserSettingId);
        var requestedIds = section.Settings.Select(setting => setting.UserSettingId).ToHashSet();
        if (existingById.Keys.Any(id => !requestedIds.Contains(id)))
            return null;
        var newIds = requestedIds.Where(id => !existingById.ContainsKey(id)).ToArray();
        if (newIds.Length > 0 && await dbContext.PlayerSettings.AsNoTracking()
            .AnyAsync(setting => newIds.Contains(setting.UserSettingId)))
            return null;

        foreach (var snapshot in section.Settings)
        {
            if (existingById.TryGetValue(snapshot.UserSettingId, out var setting))
            {
                if (!snapshot.ExpectedVersion.HasValue || setting.Version != snapshot.ExpectedVersion.Value
                    || !string.Equals(setting.SettingKey, snapshot.SettingKey.Trim(), StringComparison.OrdinalIgnoreCase))
                    return null;
                setting.SettingKey = snapshot.SettingKey.Trim();
                setting.SettingValueJson = snapshot.SettingValueJson;
                setting.Version = checked(setting.Version + 1);
                setting.UpdatedAt = now;
                setting.UpdatedBy = request.UpdatedBy;
            }
            else
            {
                if (snapshot.ExpectedVersion.HasValue)
                    return null;
                await dbContext.PlayerSettings.AddAsync(new PlayerSettingEntity
                {
                    UserSettingId = snapshot.UserSettingId, UserId = section.UserId,
                    SettingKey = snapshot.SettingKey.Trim(), SettingValueJson = snapshot.SettingValueJson,
                    Version = 1, CreatedAt = now, UpdatedAt = now,
                    CreatedBy = request.UpdatedBy, UpdatedBy = request.UpdatedBy, IsDeleted = false,
                });
            }
        }
        return new
        {
            clientRevision = section.ClientRevision,
            settings = section.Settings.OrderBy(setting => setting.SettingKey, StringComparer.OrdinalIgnoreCase).Select(setting => new
            {
                userSettingId = setting.UserSettingId,
                settingKey = setting.SettingKey.Trim(),
                version = existingById.TryGetValue(setting.UserSettingId, out var existingSetting)
                    ? existingSetting.Version : 1,
            }),
        };
    }

    private async Task<object?> ApplyMailClaimAsync(
        PlayerStateMailClaimSection section,
        AccountEntity account,
        PlayerStateSnapshotSaveRequest request,
        DateTime now)
    {
        var mailId = section.MailId.Trim();
        var mail = await FindAvailableMailAsync(mailId, request.AccountId);
        if (mail is null
            || !string.Equals(mail.Id, mailId, StringComparison.OrdinalIgnoreCase)
            || mail.IsDeleted
            || mail.PublishFrom > now
            || mail.PublishTo is { } publishTo && publishTo < now
            || mail.FirstLoginOnly && account.CreatedAt < mail.PublishFrom)
            return null;

        var state = await dbContext.PlayerMailStates
            .FirstOrDefaultAsync(value => value.AccountId == request.AccountId && value.MailId == mailId);
        if (state is { IsRead: true } or { IsDeleted: true })
            return null;

        if (state is null)
        {
            state = new PlayerMailStateEntity
            {
                PlayerMailStateId = Guid.NewGuid(),
                AccountId = request.AccountId,
                MailId = mailId,
                IsRead = true,
                ReadAt = now,
                Version = 2,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = request.UpdatedBy,
                UpdatedBy = request.UpdatedBy,
                IsDeleted = false,
            };
            await dbContext.PlayerMailStates.AddAsync(state);
        }
        else
        {
            state.IsRead = true;
            state.ReadAt = now;
            state.Version += 1;
            state.UpdatedAt = now;
            state.UpdatedBy = request.UpdatedBy;
        }

        return new
        {
            clientRevision = section.ClientRevision,
            mailId,
            version = state.Version,
            readAt = state.ReadAt,
        };
    }

    private async Task<object?> ApplyMailDeleteAsync(
        PlayerStateMailDeleteSection section,
        AccountEntity account,
        PlayerStateSnapshotSaveRequest request,
        DateTime now)
    {
        var mailId = section.MailId.Trim();
        var mail = await FindAvailableMailAsync(mailId, request.AccountId);
        if (mail is null
            || !string.Equals(mail.Id, mailId, StringComparison.OrdinalIgnoreCase)
            || mail.IsDeleted
            || mail.PublishFrom > now
            || mail.PublishTo is { } publishTo && publishTo < now
            || mail.FirstLoginOnly && account.CreatedAt < mail.PublishFrom)
            return null;

        var state = await dbContext.PlayerMailStates
            .FirstOrDefaultAsync(value => value.AccountId == request.AccountId && value.MailId == mailId);
        if (state is { IsDeleted: true })
            return null;

        if (state is null)
        {
            state = new PlayerMailStateEntity
            {
                PlayerMailStateId = Guid.NewGuid(),
                AccountId = request.AccountId,
                MailId = mailId,
                IsRead = false,
                IsDeleted = true,
                DeletedAt = now,
                Version = 2,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = request.UpdatedBy,
                UpdatedBy = request.UpdatedBy,
            };
            await dbContext.PlayerMailStates.AddAsync(state);
        }
        else
        {
            state.IsDeleted = true;
            state.DeletedAt = now;
            state.Version = Math.Max(1, state.Version + 1);
            state.UpdatedAt = now;
            state.UpdatedBy = request.UpdatedBy;
        }

        return new
        {
            clientRevision = section.ClientRevision,
            mailId,
            version = state.Version,
            deletedAt = state.DeletedAt,
        };
    }

    private async Task<MailResponse?> FindAvailableMailAsync(string mailId, Guid accountId)
    {
        var deliveryPayload = await dbContext.PlayerMailDeliveries.AsNoTracking()
            .Where(delivery => delivery.AccountId == accountId
                && delivery.MailId == mailId
                && !delivery.IsDeleted)
            .Select(delivery => delivery.PayloadJson)
            .FirstOrDefaultAsync();
        if (deliveryPayload is not null)
            return MasterDataPayloadJson.Deserialize<MailResponse>(deliveryPayload);

        if (masterDataDbContext is null)
            return null;
        var masterPayload = await masterDataDbContext.Entries.AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.MasterType == "mail" && entry.MasterId == mailId)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefaultAsync();
        return masterPayload is null ? null : MasterDataPayloadJson.Deserialize<MailResponse>(masterPayload);
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
        if (deletedById.Keys.Any(id => !byId.ContainsKey(id)))
            return null;
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
            foreach (var currentSigil in currentSigils.Values)
                currentSigil.IsDeleted = true;
            if (currentSigils.Count > 0)
                await dbContext.SaveChangesAsync();
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
        foreach (var snapshot in section.Presets)
        {
            if (byIndex.TryGetValue(snapshot.PresetIndex, out var current)
                ? snapshot.ExpectedVersion != current.Version
                : snapshot.ExpectedVersion.HasValue)
                return null;
        }
        var previouslySelected = existing.Where(preset => preset.IsSelected
            && preset.PresetIndex != section.SelectedPresetIndex).ToArray();
        foreach (var preset in previouslySelected)
            preset.IsSelected = false;
        if (previouslySelected.Length > 0)
            await dbContext.SaveChangesAsync();
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
            await dbContext.SaveChangesAsync();
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

    private async Task<IReadOnlyList<PlayerSettingEntity>> FindPlayerSettingsForUpdateAsync(Guid userId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.PlayerSettings.FromSqlInterpolated($"""
                SELECT * FROM [dbo].[user_setting] WITH (UPDLOCK, HOLDLOCK)
                WHERE [user_id] = {userId} AND [is_deleted] = 0
                ORDER BY [user_setting_id]
                """).ToListAsync();
        }
        return await dbContext.PlayerSettings
            .Where(setting => setting.UserId == userId && !setting.IsDeleted)
            .OrderBy(setting => setting.UserSettingId)
            .ToListAsync();
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

    private async Task<IReadOnlyList<InventoryEntryEntity>> FindActiveAccountEntriesForUpdateAsync(Guid accountId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.InventoryEntries.FromSqlInterpolated($"""
                SELECT entry.* FROM [dbo].[inventory_entry] entry WITH (UPDLOCK, HOLDLOCK)
                INNER JOIN [dbo].[inventory] inventory WITH (HOLDLOCK) ON inventory.[inventory_id] = entry.[inventory_id]
                WHERE inventory.[account_id] = {accountId}
                  AND inventory.[is_deleted] = 0
                  AND entry.[is_deleted] = 0
                ORDER BY entry.[inventory_entry_id]
                """).ToListAsync();
        }
        return await (from entry in dbContext.InventoryEntries
                      join inventory in dbContext.Inventories on entry.InventoryId equals inventory.InventoryId
                      where inventory.AccountId == accountId && !inventory.IsDeleted && !entry.IsDeleted
                      orderby entry.InventoryEntryId
                      select entry).ToListAsync();
    }

    private async Task<IReadOnlyList<EquipmentLoadoutEntity>> FindAccountLoadoutsForUpdateAsync(Guid accountId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.EquipmentLoadouts.FromSqlInterpolated($"""
                SELECT * FROM [dbo].[equipment_loadout] WITH (UPDLOCK, HOLDLOCK)
                WHERE [account_id] = {accountId} AND [is_deleted] = 0
                ORDER BY [equipment_loadout_id]
                """).ToListAsync();
        }
        return await dbContext.EquipmentLoadouts
            .Where(loadout => loadout.AccountId == accountId && !loadout.IsDeleted)
            .OrderBy(loadout => loadout.EquipmentLoadoutId).ToListAsync();
    }

    /// <summary>事前にロックした装備と今回作成した装備からitem IDを解決し、後出しの共有読み取りを行わない。</summary>
    private static string? ResolveEntryItemId(
        PlayerStateInventoryEntrySnapshot entry,
        Guid accountId,
        IReadOnlyDictionary<Guid, EquipmentInstanceEntity> equipmentById)
    {
        if (string.IsNullOrWhiteSpace(entry.InstanceType) || !entry.InstanceId.HasValue)
            return entry.ItemId;
        if (!string.Equals(entry.InstanceType.Trim(), "EQUIPMENT", StringComparison.OrdinalIgnoreCase))
            return null;
        var authoritativeItemId = equipmentById.TryGetValue(entry.InstanceId.Value, out var captured)
            && captured.AccountId == accountId && !captured.IsDeleted ? captured.ItemId : null;
        return string.IsNullOrWhiteSpace(authoritativeItemId)
            || (!string.IsNullOrWhiteSpace(entry.ItemId)
                && !string.Equals(entry.ItemId, authoritativeItemId, StringComparison.OrdinalIgnoreCase))
            ? null
            : authoritativeItemId;
    }

    private async Task<bool> ChildIdsBelongToSnapshotParentsAsync(PlayerStateSnapshotSaveRequest request)
    {
        if (request.Equipment.Count > 0)
        {
            var statRolls = request.Equipment.SelectMany(e => e.StatRolls.Select(c => (c.StatRollId, e.EquipmentInstanceId)))
                .ToDictionary(c => c.StatRollId, c => c.EquipmentInstanceId);
            var statRollIds = statRolls.Keys.ToArray();
            if (statRollIds.Length > 0 && await dbContext.EquipmentInstanceStatRolls.AsNoTracking()
                .AnyAsync(c => statRollIds.Contains(c.StatRollId))) return false;
            var enchants = request.Equipment.SelectMany(e => e.Enchants.Select(c => (c.EnchantId, e.EquipmentInstanceId)))
                .ToDictionary(c => c.EnchantId, c => c.EquipmentInstanceId);
            var enchantIds = enchants.Keys.ToArray();
            var existingEnchants = enchantIds.Length == 0
                ? []
                : await dbContext.EquipmentInstanceEnchants.AsNoTracking()
                    .Where(c => enchantIds.Contains(c.EnchantId)).ToListAsync();
            if (existingEnchants.Any(c => enchants[c.EnchantId] != c.EquipmentInstanceId)) return false;
            var runes = request.Equipment.SelectMany(e => e.Runes.Select(c => (c.RuneId, e.EquipmentInstanceId)))
                .ToDictionary(c => c.RuneId, c => c.EquipmentInstanceId);
            var runeIds = runes.Keys.ToArray();
            var existingRunes = runeIds.Length == 0
                ? []
                : await dbContext.EquipmentInstanceRunes.AsNoTracking()
                    .Where(c => runeIds.Contains(c.RuneId)).ToListAsync();
            if (existingRunes.Any(c => runes[c.RuneId] != c.EquipmentInstanceId)) return false;
        }
        if (request.LearnedSkills is { } json)
        {
            var section = json.Deserialize<PlayerStateLearnedSkillsSection>(JsonOptions)!;
            var skillIds = section.Skills.Select(s => s.LearnedSkillId).ToArray();
            if (skillIds.Length > 0 && await dbContext.AccountLearnedSkills.AsNoTracking().AnyAsync(s => skillIds.Contains(s.LearnedSkillId)
                && (s.AccountId != request.AccountId || s.IsDeleted))) return false;
            var sigils = section.Skills.SelectMany(s => s.Sigils.Select(c => (c.LearnedSkillSigilId, s.LearnedSkillId)))
                .ToDictionary(c => c.LearnedSkillSigilId, c => c.LearnedSkillId);
            var sigilIds = sigils.Keys.ToArray();
            var existingSigils = sigilIds.Length == 0
                ? []
                : await dbContext.AccountLearnedSkillSigils.AsNoTracking()
                    .Where(c => sigilIds.Contains(c.LearnedSkillSigilId)).ToListAsync();
            if (existingSigils.Any(c => sigils[c.LearnedSkillSigilId] != c.LearnedSkillId || c.IsDeleted)) return false;
        }
        return true;
    }

    private static bool TryValidateRoot(PlayerStateSnapshotSaveRequest request, out string? detail)
    {
        detail = "Snapshot structure is invalid.";
        if (request.Inventories is null || request.Loadouts is null || request.Equipment is null
            || request.Inventories.Any(i => i is null || i.ExpectedEntries is null || i.DeletedEntryIds is null || i.Entries is null)
            || request.Loadouts.Any(l => l is null || l.Slots is null || l.DeletedSlots is null)
            || request.Equipment.Any(e => e is null || e.StatRolls is null || e.Enchants is null || e.Runes is null))
            return false;
        if (request.Inventories.Any(i => !IsValidInventorySnapshot(i)
                || i.ExpectedEntries.Any(e => e is null || e.InventoryEntryId == Guid.Empty)
                || i.DeletedEntryIds.Any(id => id == Guid.Empty)
                || HasDuplicates(i.DeletedEntryIds)
                || (i.EntryMode is not null && !string.Equals(i.EntryMode, "DELTA", StringComparison.OrdinalIgnoreCase))
                || i.Entries.Any(e => e is null || !IsValidEntry(e) || !IsJsonOrNull(e.MetadataJson))
                || (i.MetadataDirty && !IsJsonOrNull(i.MetadataJson))
                || HasDuplicates(i.Entries.Where(e => e.SlotIndex.HasValue).Select(e => e.SlotIndex))
                || HasDuplicates(i.Entries.Where(e => e.SlotIndex is null && string.IsNullOrWhiteSpace(e.InstanceType))
                    .Select(e => e.ItemId!.Trim().ToUpperInvariant())))
            || HasDuplicates(request.Inventories.SelectMany(i => i.Entries).Select(e => e.InventoryEntryId))
            || HasDuplicates(request.Inventories.SelectMany(i => i.ExpectedEntries).Select(e => e.InventoryEntryId))
            || HasDuplicates(request.Loadouts.Select(l => l.EquipmentLoadoutId))
            || request.Loadouts.Any(l => !IsValidLoadoutSnapshot(l)
                || l.Slots.Any(s => s is null || s.EquipmentInstanceId == Guid.Empty
                    || s.SlotIndex < 0 || !ValidText(s.SlotType, 30))
                || HasDuplicates(l.Slots.Select(s => (s.SlotType.Trim().ToUpperInvariant(), s.SlotIndex)))
                || l.DeletedSlots.Any(s => s is null || s.SlotIndex < 0 || !ValidText(s.SlotType, 30))
                || HasDuplicates(l.DeletedSlots.Select(s => (s.SlotType.Trim().ToUpperInvariant(), s.SlotIndex)))
                || l.DeletedSlots.Any(deleted => l.Slots.Any(slot => string.Equals(slot.SlotType, deleted.SlotType, StringComparison.OrdinalIgnoreCase)
                    && slot.SlotIndex == deleted.SlotIndex))
                || (l.SlotMode is not null && !string.Equals(l.SlotMode, "DELTA", StringComparison.OrdinalIgnoreCase))
                || HasDuplicates(l.Slots.Select(s => s.EquipmentInstanceId)))
            || HasDuplicates(request.Equipment.Select(e => e.EquipmentInstanceId))
            || request.Equipment.Any(e => !IsValidEquipment(e))
            || HasDuplicates(request.Equipment.SelectMany(e => e.StatRolls).Select(e => e.StatRollId))
            || HasDuplicates(request.Equipment.SelectMany(e => e.Enchants).Select(e => e.EnchantId))
            || HasDuplicates(request.Equipment.SelectMany(e => e.Runes).Select(e => e.RuneId)))
            return false;
        if (!ValidateSections(request))
            return false;
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
        if (request.Inventories.Where(inventory => inventory.IsNew)
                .GroupBy(inventory => $"{inventory.InventoryType!.Trim()}\u001f{inventory.InventoryProfile!.Trim()}", StringComparer.OrdinalIgnoreCase)
                .Any(group => group.Count() > 1))
        {
            detail = "new inventory type/profile is duplicated.";
            return false;
        }
        if (request.Loadouts.Where(loadout => loadout.IsNew)
                .GroupBy(loadout => $"{loadout.LoadoutProfile!.Trim()}\u001f{loadout.LoadoutName!.Trim()}", StringComparer.OrdinalIgnoreCase)
                .Any(group => group.Count() > 1)
            || request.Loadouts.Where(loadout => loadout.IsNew && loadout.IsActive!.Value)
                .GroupBy(loadout => loadout.LoadoutProfile!.Trim(), StringComparer.OrdinalIgnoreCase)
                .Any(group => group.Count() > 1))
        {
            detail = "new loadout name or active profile is duplicated.";
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

    private static bool UsesEntryDelta(PlayerStateInventorySnapshot snapshot)
        => string.Equals(snapshot.EntryMode, "DELTA", StringComparison.OrdinalIgnoreCase);

    private static bool UsesLoadoutSlotDelta(PlayerStateLoadoutSnapshot snapshot)
        => string.Equals(snapshot.SlotMode, "DELTA", StringComparison.OrdinalIgnoreCase);

    private static bool IsValidInventorySnapshot(PlayerStateInventorySnapshot snapshot)
        => snapshot.IsNew
            ? !snapshot.ExpectedUpdatedAt.HasValue
                && snapshot.ExpectedEntries.Count == 0
                && !snapshot.MetadataDirty
                && ValidText(snapshot.InventoryType, 30)
                && ValidText(snapshot.InventoryProfile, 20)
                && snapshot.SlotCapacity is null or >= 0
                && snapshot.IsEnabled.HasValue
                && IsJsonOrNull(snapshot.MetadataJson)
            : string.IsNullOrWhiteSpace(snapshot.InventoryType)
                && string.IsNullOrWhiteSpace(snapshot.InventoryProfile)
                && !snapshot.SlotCapacity.HasValue
                && !snapshot.IsEnabled.HasValue;

    private static bool IsValidLoadoutSnapshot(PlayerStateLoadoutSnapshot snapshot)
        => snapshot.IsNew
            ? !snapshot.ExpectedUpdatedAt.HasValue
                && ValidText(snapshot.LoadoutProfile, 20)
                && ValidText(snapshot.LoadoutName, 100)
                && snapshot.SortOrder >= 0
                && snapshot.IsActive.HasValue
                && IsJsonOrNull(snapshot.MetadataJson)
            : snapshot.ExpectedUpdatedAt.HasValue
                && string.IsNullOrWhiteSpace(snapshot.LoadoutProfile)
                && string.IsNullOrWhiteSpace(snapshot.LoadoutName)
                && snapshot.SortOrder == 0
                && !snapshot.IsActive.HasValue
                && snapshot.MetadataJson is null;

    private static bool IsValidEntry(PlayerStateInventoryEntrySnapshot entry)
        => entry.InventoryEntryId != Guid.Empty
            && entry.Quantity >= 1
            && (!entry.SlotIndex.HasValue || entry.SlotIndex >= 0)
            && ValidText(entry.ItemCategory, 30)
            && (entry.ItemId is null || entry.ItemId.Length <= 100)
            && ((string.IsNullOrWhiteSpace(entry.InstanceType) && !entry.InstanceId.HasValue && !string.IsNullOrWhiteSpace(entry.ItemId))
                || (string.Equals(entry.InstanceType?.Trim(), "EQUIPMENT", StringComparison.OrdinalIgnoreCase)
                    && entry.InstanceId.HasValue && entry.InstanceId != Guid.Empty && entry.Quantity == 1));

    /// <summary>
    /// 新規・既存装備の完成状態を検証します。初回保存前のローカル強化や装着も受理し、
    /// 新規個体だけに必要な作成属性と、全個体共通の値・child 制約を照合します。
    /// </summary>
    private static bool IsValidEquipment(PlayerStateEquipmentSnapshot snapshot)
        => (snapshot.IsNew
                ? !snapshot.ExpectedUpdatedAt.HasValue
                    && ValidText(snapshot.ItemId, 100)
                    && snapshot.StatRolls.All(statRoll => statRoll is not null
                        && statRoll.StatRollId != Guid.Empty
                        && statRoll.SortOrder >= 0
                        && ValidText(statRoll.Status, 50)
                        && ValidNumericText(statRoll.Min, 20)
                        && ValidNumericText(statRoll.Max, 20))
                    && snapshot.StatRolls.GroupBy(statRoll => statRoll.StatRollId).All(group => group.Count() == 1)
                    && snapshot.StatRolls.GroupBy(
                        statRoll => (statRoll.Status.Trim().ToUpperInvariant(), statRoll.SortOrder))
                        .All(group => group.Count() == 1)
                : snapshot.ExpectedUpdatedAt.HasValue
                    && string.IsNullOrWhiteSpace(snapshot.ItemId)
                    && snapshot.StatRolls.Count == 0)
            && snapshot.EnhanceLevel >= 0
            && snapshot.RuneMaxSlots >= 0
            && snapshot.TranscendenceRank >= 0
            && ((snapshot.DurabilityMax is null && snapshot.DurabilityValue is null)
                || (snapshot.DurabilityMax is int durabilityMax && durabilityMax > 0
                    && snapshot.DurabilityValue is int durabilityValue && durabilityValue >= 0 && durabilityValue <= durabilityMax))
            && snapshot.Enchants.All(enchant => enchant is not null && enchant.EnchantId != Guid.Empty && enchant.SlotIndex >= 0
                && ValidText(enchant.EnchantMasterId, 100) && ValidText(enchant.EffectId, 100)
                && ValidText(enchant.Status, 50) && ValidText(enchant.Type, 20)
                && enchant.Value >= -99_999_999_999_999.9999m && enchant.Value <= 99_999_999_999_999.9999m)
            && snapshot.Enchants.GroupBy(enchant => enchant.EnchantId).All(group => group.Count() == 1)
            && snapshot.Enchants.GroupBy(enchant => enchant.SlotIndex).All(group => group.Count() == 1)
            && snapshot.Enchants.GroupBy(enchant => enchant.EffectId.Trim(), StringComparer.OrdinalIgnoreCase).All(group => group.Count() == 1)
            && snapshot.Runes.All(rune => rune is not null && rune.RuneId != Guid.Empty && rune.SlotIndex >= 0
                && rune.SlotIndex < snapshot.RuneMaxSlots && ValidText(rune.ItemId, 100))
            && snapshot.Runes.GroupBy(rune => rune.RuneId).All(group => group.Count() == 1)
            && snapshot.Runes.GroupBy(rune => rune.SlotIndex).All(group => group.Count() == 1);

    private static bool IsJsonOrNull(string? value)
    {
        if (value is null) return true;
        try { using var _ = JsonDocument.Parse(value); return true; }
        catch (JsonException) { return false; }
    }

    private static bool ValidJson(string? value) => !string.IsNullOrWhiteSpace(value) && IsJsonOrNull(value);

    private static T? TryDeserializeSection<T>(JsonElement element)
    {
        try { return element.Deserialize<T>(JsonOptions); }
        catch (JsonException) { return default; }
    }

    private static bool IsValidLearnedSkill(PlayerStateLearnedSkillSnapshot skill)
        => skill is not null && skill.LearnedSkillId != Guid.Empty
            && skill.Level >= 1
            && skill.ExpectedVersion is not < 1 && skill.TargetVersion is not < 1
            && ValidText(skill.SkillId, 100) && skill.Sigils is not null
            && skill.Sigils.All(sigil => sigil is not null && sigil.LearnedSkillSigilId != Guid.Empty
                && sigil.SlotIndex >= 0 && ValidText(sigil.SigilId, 100)
                && ValidText(sigil.EquipGroupId, 100))
            && skill.Sigils.GroupBy(sigil => sigil.LearnedSkillSigilId).All(group => group.Count() == 1)
            && skill.Sigils.GroupBy(sigil => sigil.SlotIndex).All(group => group.Count() == 1)
            && skill.Sigils.GroupBy(sigil => sigil.EquipGroupId.Trim(), StringComparer.OrdinalIgnoreCase).All(group => group.Count() == 1);

    private static bool HasDuplicates<T>(IEnumerable<T> values) => values.GroupBy(value => value).Any(group => group.Count() > 1);

    private static bool ValidText(string? value, int maxLength) => !string.IsNullOrWhiteSpace(value) && value.Trim().Length <= maxLength;

    private static bool ValidNumericText(string? value, int maxLength)
        => ValidText(value, maxLength)
            && decimal.TryParse(value!.Trim(), NumberStyles.Number, CultureInfo.InvariantCulture, out _);

    private static bool ValidEpochMillis(long value)
    {
        try
        {
            _ = DateTimeOffset.FromUnixTimeMilliseconds(value);
            return true;
        }
        catch (ArgumentOutOfRangeException)
        {
            return false;
        }
    }

    private static DateTime FromEpochMillis(long value) =>
        DateTimeOffset.FromUnixTimeMilliseconds(value).UtcDateTime;

    private static bool ValidateSections(PlayerStateSnapshotSaveRequest request)
    {
        if (request.LearnedSkills is { } learnedJson)
        {
            var section = TryDeserializeSection<PlayerStateLearnedSkillsSection>(learnedJson);
            if (section is null || section.AccountId != request.AccountId || section.ClientRevision < 0
                || section.Skills is null || section.DeletedSkills is null
                || section.Skills.Any(s => !IsValidLearnedSkill(s))
                || section.DeletedSkills.Any(s => s is null || s.LearnedSkillId == Guid.Empty || s.ExpectedVersion < 1)
                || HasDuplicates(section.Skills.Select(s => s.LearnedSkillId).Concat(section.DeletedSkills.Select(s => s.LearnedSkillId)))
                || HasDuplicates(section.Skills.SelectMany(s => s.Sigils).Select(s => s.LearnedSkillSigilId)))
                return false;
        }
        if (request.SkillBindPresets is { } bindJson)
        {
            var section = TryDeserializeSection<PlayerStateSkillBindPresetsSection>(bindJson);
            if (section is null || section.AccountId != request.AccountId || section.ClientRevision < 0
                || section.Presets is null || section.Presets.Count != SkillBindPresetRepository.PresetCount
                || section.SelectedPresetIndex is < 1 or > SkillBindPresetRepository.PresetCount
                || section.Presets.Any(p => p is null || p.PresetIndex is < 1 or > SkillBindPresetRepository.PresetCount
                    || p.ExpectedVersion is < 1 || p.TargetVersion is < 1
                    || p.ActiveSkillSlots is null || p.PassiveSkillSlots is null
                    || p.ActiveSkillSlots.Count > SkillBindPresetRepository.ActionRingSlotCount
                    || p.PassiveSkillSlots.Count > SkillBindPresetRepository.PassiveSlotCount)
                || HasDuplicates(section.Presets.Select(p => p.PresetIndex)))
                return false;
        }
        if (request.SkillTree is { } treeJson)
        {
            var section = TryDeserializeSection<PlayerStateSkillTreeSection>(treeJson);
            if (section is null || section.AccountId != request.AccountId || section.ClientRevision < 0
                || section.ExpectedVersion is < 0 || section.TargetVersion is < 1 || section.UnlockedNodes is null
                || section.UnlockedNodes.Any(n => n is null || !ValidText(n.NodeId, 100)
                    || (n.ConsumedClassId is not null && !ValidText(n.ConsumedClassId, 100)))
                || HasDuplicates(section.UnlockedNodes.Select(n => n.NodeId.Trim().ToUpperInvariant())))
                return false;
        }
        if (request.AccountProgress is { } progressJson)
        {
            var section = TryDeserializeSection<PlayerStateAccountProgressSection>(progressJson);
            if (section is null || section.AccountId != request.AccountId || section.ClientRevision < 0
                || section.ExpectedProgressVersion < 1 || section.Level < 1 || section.TotalExperience < 0
                || section.ClassLevel < 1 || section.ClassExperience < 0 || !ValidText(section.ClassId, 100)
                || (section.Mode.HasValue && section.Mode is not (0 or 2)) || section.ClassProgresses is null
                || section.ClassProgresses.Any(p => p is null || !ValidText(p.ClassId, 100) || p.Level < 1 || p.Experience < 0)
                || HasDuplicates(section.ClassProgresses.Select(p => p.ClassId.Trim().ToUpperInvariant())))
                return false;
        }
        if (request.Waystones is { } waystonesJson)
        {
            var section = TryDeserializeSection<PlayerStateWaystonesSection>(waystonesJson);
            if (section is null || section.ClientRevision < 0 || section.UnlockedWaystoneIds is null
                || section.UnlockedWaystoneIds.Any(id => !ValidText(id, 100) || id != id.Trim())
                || HasDuplicates(section.UnlockedWaystoneIds.Select(id => id.ToUpperInvariant())))
                return false;
        }
        if (request.QuestState is { } questJson)
        {
            var section = TryDeserializeSection<PlayerStateQuestStateSection>(questJson);
            if (section is null || section.AccountId != request.AccountId || section.ClientRevision < 0
                || section.ExpectedVersion < 0 || section.ActiveQuests is null
                || section.Completions is null || section.Cooldowns is null
                || section.ActiveQuests.Any(active => active is null || !ValidText(active.QuestId, 100)
                    || !ValidEpochMillis(active.AcceptedAtEpochMillis)
                    || active.AcceptedNpcId is not null && !ValidText(active.AcceptedNpcId, 100)
                    || active.ObjectiveProgress is null
                    || active.ObjectiveProgress.Any(objective => objective is null
                        || !ValidText(objective.ObjectiveId, 100) || objective.Progress < 0)
                    || HasDuplicates(active.ObjectiveProgress.Select(
                        objective => objective.ObjectiveId.Trim().ToUpperInvariant())))
                || section.Completions.Any(completion => completion is null
                    || !ValidText(completion.QuestId, 100)
                    || !ValidEpochMillis(completion.CompletedAtEpochMillis))
                || section.Cooldowns.Any(cooldown => cooldown is null
                    || !ValidText(cooldown.QuestId, 100)
                    || !ValidEpochMillis(cooldown.CooldownUntilEpochMillis))
                || HasDuplicates(section.ActiveQuests.Select(active => active.QuestId.Trim().ToUpperInvariant()))
                || HasDuplicates(section.Completions.Select(completion => completion.QuestId.Trim().ToUpperInvariant()))
                || HasDuplicates(section.Cooldowns.Select(cooldown => cooldown.QuestId.Trim().ToUpperInvariant())))
                return false;
        }
        if (request.LoginBonusClaims is { } loginBonusJson)
        {
            var section = TryDeserializeSection<PlayerStateLoginBonusClaimsSection>(loginBonusJson);
            if (section is null || section.ClientRevision < 0 || section.ClaimDates is null
                || section.ClaimDates.Any(date => date == default)
                || HasDuplicates(section.ClaimDates))
                return false;
        }
        if (request.GuideProgress is { } guideProgressJson)
        {
            var section = TryDeserializeSection<PlayerStateGuideProgressSection>(guideProgressJson);
            if (section is null || section.AccountId != request.AccountId || section.ClientRevision < 0
                || section.CompletedStepKeys is null
                || section.CompletedStepKeys.Any(step => step is null || !ValidText(step.GuideId, 100) || !ValidText(step.StepId, 100))
                || HasDuplicates(section.CompletedStepKeys.Select(step =>
                    $"{step.GuideId.Trim()}\u001f{step.StepId.Trim()}".ToUpperInvariant())))
                return false;
        }
        if (request.AdventureRecords is { } adventureRecordsJson)
        {
            var section = TryDeserializeSection<PlayerStateAdventureRecordsSection>(adventureRecordsJson);
            if (section is null || section.AccountId != request.AccountId || section.ClientRevision < 0
                || section.MobDefeatDeltas is null || section.DungeonClearDeltas is null
                || section.MobDefeatDeltas.Any(delta => delta is null || !ValidText(delta.MobId, 100)
                    || !ValidText(delta.MobCategory, 20)
                    || !string.Equals(delta.MobCategory.Trim(), "ENEMY", StringComparison.OrdinalIgnoreCase)
                        && !string.Equals(delta.MobCategory.Trim(), "BOSS", StringComparison.OrdinalIgnoreCase)
                    || delta.Delta < 1)
                || section.DungeonClearDeltas.Any(delta => delta is null || !ValidText(delta.DungeonId, 100) || delta.Delta < 1)
                || HasDuplicates(section.MobDefeatDeltas.Select(delta => delta.MobId.Trim().ToUpperInvariant()))
                || HasDuplicates(section.DungeonClearDeltas.Select(delta => delta.DungeonId.Trim().ToUpperInvariant())))
                return false;
        }
        if (request.PlayerSettings is { } playerSettingsJson)
        {
            var section = TryDeserializeSection<PlayerStatePlayerSettingsSection>(playerSettingsJson);
            if (section is null || section.UserId == Guid.Empty || section.ClientRevision < 0 || section.Settings is null
                || section.Settings.Any(setting => setting is null || setting.UserSettingId == Guid.Empty
                    || !ValidText(setting.SettingKey, 100) || setting.SettingKey != setting.SettingKey.Trim()
                    || !ValidJson(setting.SettingValueJson) || setting.ExpectedVersion is < 1)
                || HasDuplicates(section.Settings.Select(setting => setting.UserSettingId))
                || HasDuplicates(section.Settings.Select(setting => setting.SettingKey.Trim().ToUpperInvariant())))
                return false;
        }
        if (request.MailClaim is { } mailClaimJson)
        {
            var section = TryDeserializeSection<PlayerStateMailClaimSection>(mailClaimJson);
            if (section is null || section.AccountId != request.AccountId || section.ClientRevision == Guid.Empty
                || !ValidText(section.MailId, 100) || section.MailId != section.MailId.Trim())
                return false;
        }
        if (request.MailDelete is { } mailDeleteJson)
        {
            var section = TryDeserializeSection<PlayerStateMailDeleteSection>(mailDeleteJson);
            if (section is null || section.AccountId != request.AccountId || section.ClientRevision == Guid.Empty
                || !ValidText(section.MailId, 100) || section.MailId != section.MailId.Trim())
                return false;
        }
        return true;
    }

    private static IReadOnlyList<string?> NormalizeSlots(IReadOnlyList<string?> values, int count)
        => Enumerable.Range(0, count)
            .Select(index => index < values.Count && !string.IsNullOrWhiteSpace(values[index]) ? values[index]!.Trim() : null)
            .ToArray();

    private static string ComputeRequestHash(PlayerStateSnapshotSaveRequest request)
        => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(request, JsonOptions)))).ToLowerInvariant();

    private static string GuideStepKey(string guideId, string stepId)
        => $"{guideId.Trim()}\u001f{stepId.Trim()}";

    private static PlayerStateSnapshotSaveResult Success(PlayerStateSnapshotAck ack)
        => new(ack, PlayerStateSnapshotSaveFailure.None);

    private static PlayerStateSnapshotSaveResult Failure(PlayerStateSnapshotSaveFailure failure, string detail)
        => new(null, failure, detail);

    private static DateTime RoundToMilliseconds(DateTime value)
        => new(value.Ticks - value.Ticks % TimeSpan.TicksPerMillisecond, DateTimeKind.Utc);

    private static DateTime AdvanceUpdatedAt(DateTime current, DateTime candidate)
        => candidate > current.AddMilliseconds(1) ? candidate : current.AddMilliseconds(1);
}
