using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Utilities;

namespace AstralRecordApi.Services;

public class EquipmentService(IItemRepository itemRepository, IEquipmentRepository equipmentRepository, IRuneRepository runeRepository, IAccountRepository accountRepository) : IEquipmentService
{
    public async Task<EquipmentInstanceResponse?> CreateAsync(EquipmentCreateRequest request)
    {
        var account = await accountRepository.GetByUuidAsync(request.AccountId);
        if (account is null)
            return null;

        var item = itemRepository.GetById(request.EquipmentId);
        if (item?.Equipment is null)
            return null;

        var equipment = item.Equipment;
        var now = DateTime.UtcNow;
        var instanceId = Guid.NewGuid();
        var runeMaxSlots = equipment.Rune is not null
            ? RangeValueResolver.ResolveInt(equipment.Rune.MaxSlots)
            : 0;

        var instance = new EquipmentInstanceEntity
        {
            EquipmentInstanceId = instanceId,
            AccountId = request.AccountId,
            ItemId = request.EquipmentId,
            EnhanceLevel = 0,
            RuneMaxSlots = runeMaxSlots,
            TranscendenceRank = 0,
            DurabilityMax = equipment.Durability?.Max,
            DurabilityValue = equipment.Durability?.Max,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = request.CreatedBy,
            UpdatedBy = request.CreatedBy,
            IsDeleted = false,
        };

        var statRolls = BuildStatRolls(instanceId, equipment.Stats, request.CreatedBy, now);
        var enchantPools = BuildEnchantPools(instanceId, equipment, request.CreatedBy, now);

        await equipmentRepository.AddAsync(instance, statRolls, enchantPools);

        return MapToResponse(instance, statRolls, [], [], enchantPools);
    }

    public async Task<EquipmentInstanceResponse?> GetByInstanceIdAsync(Guid instanceId)
    {
        var instance = await equipmentRepository.FindInstanceAsync(instanceId);
        if (instance is null)
            return null;

        return await BuildResponseAsync(instance);
    }

    public async Task<EquipmentInstanceResponse?> EnchantAsync(EquipmentEnchantRequest request)
    {
        if (request.PoolIndex < 0)
            return null;

        var instance = await equipmentRepository.FindInstanceAsync(request.EquipmentInstanceId);
        if (instance is null)
            return null;

        var item = itemRepository.GetById(instance.ItemId);
        if (item?.Equipment?.Enchant is null || request.PoolIndex >= item.Equipment.Enchant.Pools.Count)
            return null;

        var maxSlots = GetEffectiveEnchantMaxSlots(item.Equipment, instance.TranscendenceRank);
        if (maxSlots <= 0)
            return null;

        var pool = item.Equipment.Enchant.Pools[request.PoolIndex];
        var entry = SelectRandomEnchantEntry(pool.Entries);
        if (entry is null || string.IsNullOrWhiteSpace(entry.Status) || string.IsNullOrWhiteSpace(entry.Type) || string.IsNullOrWhiteSpace(entry.Value))
            return null;

        var now = DateTime.UtcNow;
        var currentEnchants = await equipmentRepository.FindEnchantsAsync(instance.EquipmentInstanceId);
        var existingForPool = currentEnchants.FirstOrDefault(x => x.PoolIndex == request.PoolIndex);

        Guid? overwriteEnchantId = existingForPool?.EnchantId;
        var slotIndex = existingForPool?.SlotIndex ?? -1;
        var createdAt = existingForPool?.CreatedAt ?? now;
        var createdBy = existingForPool?.CreatedBy ?? request.UpdatedBy;

        if (slotIndex < 0)
        {
            if (currentEnchants.Count < maxSlots)
            {
                slotIndex = GetNextAvailableSlotIndex(currentEnchants, maxSlots);
            }
            else
            {
                var overwriteTarget = currentEnchants[Random.Shared.Next(currentEnchants.Count)];
                overwriteEnchantId = overwriteTarget.EnchantId;
                slotIndex = overwriteTarget.SlotIndex;
                createdAt = overwriteTarget.CreatedAt;
                createdBy = overwriteTarget.CreatedBy;
            }
        }

        instance.UpdatedAt = now;
        instance.UpdatedBy = request.UpdatedBy;

        var enchant = new EquipmentInstanceEnchantEntity
        {
            EnchantId = overwriteEnchantId ?? Guid.NewGuid(),
            EquipmentInstanceId = instance.EquipmentInstanceId,
            SlotIndex = slotIndex,
            PoolIndex = request.PoolIndex,
            Status = entry.Status,
            Type = entry.Type,
            Value = RangeValueResolver.ResolveDecimal(entry.Value),
            CreatedAt = createdAt,
            UpdatedAt = now,
            CreatedBy = createdBy,
            UpdatedBy = request.UpdatedBy,
        };

        await equipmentRepository.ApplyEnchantAsync(instance, enchant, overwriteEnchantId);
        return await GetByInstanceIdAsync(instance.EquipmentInstanceId);
    }

    public async Task<EquipmentInstanceResponse?> DeleteEnchantAsync(EquipmentEnchantDeleteRequest request)
    {
        var instance = await equipmentRepository.FindInstanceAsync(request.EquipmentInstanceId);
        if (instance is null)
            return null;

        var deleted = await equipmentRepository.DeleteEnchantByPoolIndexAsync(request.EquipmentInstanceId, request.PoolIndex);
        if (!deleted)
            return null;

        return await GetByInstanceIdAsync(request.EquipmentInstanceId);
    }

    public async Task<EquipmentInstanceResponse?> EnhanceAsync(EquipmentEnhanceRequest request)
    {
        var instance = await equipmentRepository.FindInstanceAsync(request.EquipmentInstanceId);
        if (instance is null)
            return null;

        var item = itemRepository.GetById(instance.ItemId);
        if (item?.Equipment?.Enhance is null)
            return null;

        var maxLevel = GetEffectiveEnhanceMaxLevel(item.Equipment, instance.TranscendenceRank);
        var targetLevel = request.TargetLevel ?? (instance.EnhanceLevel + 1);

        if (targetLevel <= instance.EnhanceLevel || targetLevel > maxLevel)
            return null;

        var durabilityBonus = item.Equipment.Enhance.Levels
            .Where(level => level.Level > instance.EnhanceLevel && level.Level <= targetLevel)
            .Sum(level => level.DurabilityBonus ?? 0);

        instance.EnhanceLevel = targetLevel;
        if (durabilityBonus > 0 && instance.DurabilityMax.HasValue && instance.DurabilityValue.HasValue)
        {
            instance.DurabilityMax += durabilityBonus;
            instance.DurabilityValue += durabilityBonus;
        }

        instance.UpdatedAt = DateTime.UtcNow;
        instance.UpdatedBy = request.UpdatedBy;

        await equipmentRepository.UpdateInstanceAsync(instance);
        return await GetByInstanceIdAsync(instance.EquipmentInstanceId);
    }

    public async Task<EquipmentInstanceResponse?> TranscendAsync(EquipmentTranscendenceRequest request)
    {
        var instance = await equipmentRepository.FindInstanceAsync(request.EquipmentInstanceId);
        if (instance is null)
            return null;

        var item = itemRepository.GetById(instance.ItemId);
        if (item?.Equipment is null || item.Equipment.Transcendence.Count == 0)
            return null;

        var targetRank = request.TargetRank
            ?? item.Equipment.Transcendence
                .Where(t => t.Rank > instance.TranscendenceRank)
                .OrderBy(t => t.Rank)
                .Select(t => t.Rank)
                .FirstOrDefault();

        if (targetRank <= instance.TranscendenceRank)
            return null;

        var target = item.Equipment.Transcendence.FirstOrDefault(t => t.Rank == targetRank);
        if (target is null)
            return null;

        instance.TranscendenceRank = targetRank;
        if (target.Overrides?.Rune is not null && !string.IsNullOrWhiteSpace(target.Overrides.Rune.MaxSlots))
            instance.RuneMaxSlots = RangeValueResolver.ResolveInt(target.Overrides.Rune.MaxSlots);

        instance.UpdatedAt = DateTime.UtcNow;
        instance.UpdatedBy = request.UpdatedBy;

        await equipmentRepository.UpdateInstanceAsync(instance);
        return await GetByInstanceIdAsync(instance.EquipmentInstanceId);
    }

    public async Task<EquipmentInstanceResponse?> AttachRuneAsync(EquipmentRuneAttachRequest request)
    {
        var instance = await equipmentRepository.FindInstanceAsync(request.EquipmentInstanceId);
        if (instance is null)
            return null;

        var equipmentItem = itemRepository.GetById(instance.ItemId);

        RuneInstanceEntity? runeInstance = null;
        var runeItemId = request.RuneItemId;

        if (request.RuneInstanceId.HasValue)
        {
            runeInstance = await runeRepository.FindInstanceAsync(request.RuneInstanceId.Value);
            if (runeInstance is null)
                return null;

            runeItemId = runeInstance.ItemId;
        }

        var runeItem = string.IsNullOrWhiteSpace(runeItemId) ? null : itemRepository.GetById(runeItemId);
        if (equipmentItem?.Equipment?.Rune is null || runeItem?.Rune is null)
            return null;

        if (!CanAttachRune(equipmentItem.Equipment, runeItem, instance))
            return null;

        var currentRunes = await equipmentRepository.FindRunesAsync(instance.EquipmentInstanceId);
        var slotIndex = request.SlotIndex ?? GetNextAvailableRuneSlot(currentRunes, instance.RuneMaxSlots);

        if (slotIndex < 0 || slotIndex >= instance.RuneMaxSlots)
            return null;

        var existing = currentRunes.FirstOrDefault(r => r.SlotIndex == slotIndex);
        var now = DateTime.UtcNow;
        instance.UpdatedAt = now;
        instance.UpdatedBy = request.UpdatedBy;

        var rune = new EquipmentInstanceRuneEntity
        {
            RuneId = existing?.RuneId ?? Guid.NewGuid(),
            EquipmentInstanceId = instance.EquipmentInstanceId,
            RuneInstanceId = runeInstance?.RuneInstanceId,
            SlotIndex = slotIndex,
            ItemId = runeItemId ?? string.Empty,
            CreatedAt = existing?.CreatedAt ?? now,
            UpdatedAt = now,
            CreatedBy = existing?.CreatedBy ?? request.UpdatedBy,
            UpdatedBy = request.UpdatedBy,
        };

        await equipmentRepository.UpsertRuneAsync(instance, rune);
        return await GetByInstanceIdAsync(instance.EquipmentInstanceId);
    }

    public async Task<EquipmentInstanceResponse?> DetachRuneAsync(EquipmentRuneDetachRequest request)
    {
        var instance = await equipmentRepository.FindInstanceAsync(request.EquipmentInstanceId);
        if (instance is null)
            return null;

        var deleted = await equipmentRepository.DeleteRuneBySlotIndexAsync(request.EquipmentInstanceId, request.SlotIndex);
        if (!deleted)
            return null;

        return await GetByInstanceIdAsync(request.EquipmentInstanceId);
    }

    private async Task<EquipmentInstanceResponse> BuildResponseAsync(EquipmentInstanceEntity instance)
    {
        var statRolls = await equipmentRepository.FindStatRollsAsync(instance.EquipmentInstanceId);
        var enchants = await equipmentRepository.FindEnchantsAsync(instance.EquipmentInstanceId);
        var runes = await equipmentRepository.FindRunesAsync(instance.EquipmentInstanceId);
        var item = itemRepository.GetById(instance.ItemId);
        var enchantPools = item?.Equipment is null
            ? []
            : BuildEnchantPools(instance.EquipmentInstanceId, item.Equipment, instance.CreatedBy, instance.CreatedAt);

        return MapToResponse(instance, statRolls, enchants, runes, enchantPools);
    }

    private static IReadOnlyList<EquipmentInstanceStatRollEntity> BuildStatRolls(
        Guid instanceId,
        IReadOnlyList<ItemEquipmentStatResponse> stats,
        Guid createdBy,
        DateTime now)
    {
        var result = new List<EquipmentInstanceStatRollEntity>();
        var sortOrder = 0;

        foreach (var stat in stats)
        {
            if (stat.Status is null || stat.Value is null)
                continue;

            var min = stat.Value.Min.Trim();
            var max = stat.Value.Max.Trim();

            if (string.IsNullOrWhiteSpace(min) || string.IsNullOrWhiteSpace(max))
                continue;

            var resolvedMin = RangeValueResolver.ResolveNumericString(min);
            var resolvedMax = RangeValueResolver.ResolveNumericString(max);

            result.Add(new EquipmentInstanceStatRollEntity
            {
                StatRollId = Guid.NewGuid(),
                EquipmentInstanceId = instanceId,
                Status = stat.Status,
                RandomMin = resolvedMin,
                RandomMax = resolvedMax,
                SortOrder = sortOrder++,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = createdBy,
                UpdatedBy = createdBy,
            });
        }

        return result;
    }

    private static IReadOnlyList<EquipmentInstanceEnchantPoolEntity> BuildEnchantPools(
        Guid instanceId,
        ItemEquipmentResponse equipment,
        Guid createdBy,
        DateTime now)
    {
        if (equipment.Enchant is null)
            return [];

        var result = new List<EquipmentInstanceEnchantPoolEntity>(equipment.Enchant.Pools.Count);

        for (var i = 0; i < equipment.Enchant.Pools.Count; i++)
        {
            var pool = equipment.Enchant.Pools[i];
            result.Add(new EquipmentInstanceEnchantPoolEntity
            {
                EnchantPoolId = Guid.NewGuid(),
                EquipmentInstanceId = instanceId,
                PoolIndex = i,
                RecipeId = pool.RecipeId,
                RequiredMaterialItemId = pool.RequiredMaterial?.ItemId,
                RequiredMaterialAmount = pool.RequiredMaterial?.Amount ?? 1,
                RequiredCurrency = pool.RequiredCurrency,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = createdBy,
                UpdatedBy = createdBy,
                IsDeleted = false,
            });
        }

        return result;
    }

    private static ItemEquipmentEnchantEntryResponse? SelectRandomEnchantEntry(IReadOnlyList<ItemEquipmentEnchantEntryResponse> entries)
    {
        var candidates = entries
            .Where(entry => !string.IsNullOrWhiteSpace(entry.Status)
                && !string.IsNullOrWhiteSpace(entry.Type)
                && !string.IsNullOrWhiteSpace(entry.Value))
            .ToList();

        if (candidates.Count == 0)
            return null;

        var totalWeight = candidates.Sum(entry => Math.Max(entry.Weight, 1));
        var roll = Random.Shared.Next(1, totalWeight + 1);

        foreach (var candidate in candidates)
        {
            roll -= Math.Max(candidate.Weight, 1);
            if (roll <= 0)
                return candidate;
        }

        return candidates[^1];
    }

    private static int GetNextAvailableSlotIndex(IReadOnlyList<EquipmentInstanceEnchantEntity> enchants, int maxSlots)
    {
        var usedSlots = enchants.Select(e => e.SlotIndex).ToHashSet();
        for (var i = 0; i < maxSlots; i++)
        {
            if (!usedSlots.Contains(i))
                return i;
        }

        return 0;
    }

    private static int GetNextAvailableRuneSlot(IReadOnlyList<EquipmentInstanceRuneEntity> runes, int maxSlots)
    {
        var usedSlots = runes.Select(e => e.SlotIndex).ToHashSet();
        for (var i = 0; i < maxSlots; i++)
        {
            if (!usedSlots.Contains(i))
                return i;
        }

        return -1;
    }

    private static bool CanAttachRune(ItemEquipmentResponse equipment, ItemResponse runeItem, EquipmentInstanceEntity instance)
    {
        if (equipment.Rune is null || runeItem.Rune is null || instance.RuneMaxSlots <= 0)
            return false;

        if (instance.EnhanceLevel < runeItem.Rune.RequiredEnhanceLevel)
            return false;

        if (equipment.Rune.AllowedRuneIds.Count > 0
            && !equipment.Rune.AllowedRuneIds.Any(id => string.Equals(id, runeItem.Id, StringComparison.OrdinalIgnoreCase)))
            return false;

        return runeItem.Rune.TargetSlots.Any(slot => string.Equals(slot, "ANY", StringComparison.OrdinalIgnoreCase)
            || string.Equals(slot, equipment.Slot, StringComparison.OrdinalIgnoreCase));
    }

    private static int GetEffectiveEnchantMaxSlots(ItemEquipmentResponse equipment, int transcendenceRank)
    {
        var maxSlots = equipment.Enchant?.MaxSlots ?? 0;
        foreach (var transcendence in equipment.Transcendence.OrderBy(x => x.Rank))
        {
            if (transcendence.Rank > transcendenceRank)
                break;

            if (transcendence.Overrides?.Enchant is not null)
                maxSlots = transcendence.Overrides.Enchant.MaxSlots;
        }

        return maxSlots;
    }

    private static int GetEffectiveEnhanceMaxLevel(ItemEquipmentResponse equipment, int transcendenceRank)
    {
        var maxLevel = equipment.Enhance?.MaxLevel ?? 0;
        foreach (var transcendence in equipment.Transcendence.OrderBy(x => x.Rank))
        {
            if (transcendence.Rank > transcendenceRank)
                break;

            if (transcendence.Overrides?.Enhance is not null)
                maxLevel = transcendence.Overrides.Enhance.MaxLevel;
        }

        return maxLevel;
    }

    private static EquipmentInstanceResponse MapToResponse(
        EquipmentInstanceEntity instance,
        IEnumerable<EquipmentInstanceStatRollEntity> statRolls,
        IEnumerable<EquipmentInstanceEnchantEntity> enchants,
        IEnumerable<EquipmentInstanceRuneEntity> runes,
        IEnumerable<EquipmentInstanceEnchantPoolEntity> enchantPools) => new()
    {
        EquipmentInstanceId = instance.EquipmentInstanceId,
        AccountId = instance.AccountId,
        ItemId = instance.ItemId,
        EnhanceLevel = instance.EnhanceLevel,
        RuneMaxSlots = instance.RuneMaxSlots,
        TranscendenceRank = instance.TranscendenceRank,
        DurabilityMax = instance.DurabilityMax,
        DurabilityValue = instance.DurabilityValue,
        CreatedAt = instance.CreatedAt,
        UpdatedAt = instance.UpdatedAt,
        StatRolls = statRolls.Select(r => new EquipmentInstanceStatRollResponse
        {
            StatRollId = r.StatRollId,
            Status = r.Status,
            Min = r.RandomMin,
            Max = r.RandomMax,
            SortOrder = r.SortOrder,
        }).ToList(),
        Enchants = enchants.Select(e => new EquipmentInstanceEnchantResponse
        {
            EnchantId = e.EnchantId,
            EquipmentInstanceId = e.EquipmentInstanceId,
            SlotIndex = e.SlotIndex,
            PoolIndex = e.PoolIndex,
            Status = e.Status,
            Type = e.Type,
            Value = e.Value,
            CreatedAt = e.CreatedAt,
            UpdatedAt = e.UpdatedAt,
            CreatedBy = e.CreatedBy,
            UpdatedBy = e.UpdatedBy,
        }).OrderBy(e => e.SlotIndex).ToList(),
        Runes = runes.Select(r => new EquipmentInstanceRuneResponse
        {
            RuneId = r.RuneId,
            RuneInstanceId = r.RuneInstanceId,
            EquipmentInstanceId = r.EquipmentInstanceId,
            SlotIndex = r.SlotIndex,
            ItemId = r.ItemId,
            CreatedAt = r.CreatedAt,
            UpdatedAt = r.UpdatedAt,
            CreatedBy = r.CreatedBy,
            UpdatedBy = r.UpdatedBy,
        }).OrderBy(r => r.SlotIndex).ToList(),
        EnchantPools = enchantPools.Select(p => new EquipmentInstanceEnchantPoolResponse
        {
            PoolIndex = p.PoolIndex,
            RecipeId = p.RecipeId,
            RequiredMaterialItemId = p.RequiredMaterialItemId,
            RequiredMaterialAmount = p.RequiredMaterialAmount,
            RequiredCurrency = p.RequiredCurrency,
        }).ToList(),
    };
}
