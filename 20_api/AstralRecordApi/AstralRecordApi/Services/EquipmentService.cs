using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Utilities;

namespace AstralRecordApi.Services;

public class EquipmentService(
    IItemRepository itemRepository,
    IEquipmentRepository equipmentRepository,
    IEquipmentOrbOperationRepository equipmentOrbOperationRepository,
    IAccountRepository accountRepository) : IEquipmentService
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
        await equipmentRepository.AddAsync(instance, statRolls);

        return MapToResponse(instance, statRolls, [], []);
    }

    public async Task<EquipmentInstanceResponse?> GetByInstanceIdAsync(Guid instanceId)
    {
        var instance = await equipmentRepository.FindInstanceAsync(instanceId);
        if (instance is null)
            return null;

        return await BuildResponseAsync(instance);
    }

    public Task<EquipmentOrbOperationResponse> ApplyOrbAsync(EquipmentOrbOperationRequest request)
        => equipmentOrbOperationRepository.ExecuteAsync(request);

    public Task<EquipmentOrbOperationResponse?> FindOrbOperationAsync(Guid operationId, Guid accountId)
        => equipmentOrbOperationRepository.FindAsync(operationId, accountId);

    public async Task<EquipmentInstanceResponse?> DeleteEnchantAsync(EquipmentEnchantDeleteRequest request)
    {
        var instance = await equipmentRepository.FindInstanceAsync(request.EquipmentInstanceId);
        if (instance is null || instance.AccountId != request.UpdatedBy)
            return null;

        var deleted = await equipmentRepository.DeleteEnchantBySlotIndexAsync(
            request.EquipmentInstanceId,
            request.SlotIndex,
            request.UpdatedBy);
        if (!deleted)
            return null;

        return await GetByInstanceIdAsync(request.EquipmentInstanceId);
    }

    public async Task<EquipmentInstanceResponse?> UpdateDurabilityAsync(EquipmentDurabilityUpdateRequest request)
    {
        var instance = await equipmentRepository.UpdateDurabilityAsync(
            request.EquipmentInstanceId,
            request.DurabilityValue,
            request.UpdatedBy);
        return instance is null ? null : await BuildResponseAsync(instance);
    }

    public async Task<bool> DeleteAsync(Guid instanceId)
        => await equipmentRepository.SoftDeleteInstanceAsync(instanceId);

    public async Task<EquipmentInstanceResponse?> AttachRuneAsync(EquipmentRuneAttachRequest request)
    {
        var instance = await equipmentRepository.FindInstanceAsync(request.EquipmentInstanceId);
        if (instance is null || instance.AccountId != request.UpdatedBy)
            return null;

        var equipmentItem = itemRepository.GetById(instance.ItemId);

        var runeItemId = request.RuneItemId.Trim();
        var runeItem = itemRepository.GetById(runeItemId);
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
            RuneInstanceId = null,
            SlotIndex = slotIndex,
            ItemId = runeItemId,
            CreatedAt = existing?.CreatedAt ?? now,
            UpdatedAt = now,
            CreatedBy = existing?.CreatedBy ?? request.UpdatedBy,
            UpdatedBy = request.UpdatedBy,
        };

        var saved = await equipmentRepository.UpsertRuneAsync(
            instance.EquipmentInstanceId,
            request.UpdatedBy,
            rune);
        if (!saved)
            return null;
        return await GetByInstanceIdAsync(instance.EquipmentInstanceId);
    }

    public async Task<EquipmentInstanceResponse?> DetachRuneAsync(EquipmentRuneDetachRequest request)
    {
        var instance = await equipmentRepository.FindInstanceAsync(request.EquipmentInstanceId);
        if (instance is null || instance.AccountId != request.UpdatedBy)
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
        return MapToResponse(instance, statRolls, enchants, runes);
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

    private static EquipmentInstanceResponse MapToResponse(
        EquipmentInstanceEntity instance,
        IEnumerable<EquipmentInstanceStatRollEntity> statRolls,
        IEnumerable<EquipmentInstanceEnchantEntity> enchants,
        IEnumerable<EquipmentInstanceRuneEntity> runes) => new()
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
            EnchantMasterId = e.EnchantMasterId,
            EffectId = e.EffectId,
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
    };
}
