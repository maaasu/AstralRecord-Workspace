using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class EquipmentLoadoutRepository(AstralRecordDbContext dbContext) : IEquipmentLoadoutRepository
{
    public async Task<IReadOnlyList<EquipmentLoadoutResponse>> GetByAccountIdAsync(Guid accountId, string? loadoutProfile)
    {
        var query = dbContext.EquipmentLoadouts
            .AsNoTracking()
            .Where(x => x.AccountId == accountId && !x.IsDeleted);

        if (!string.IsNullOrWhiteSpace(loadoutProfile))
            query = query.Where(x => x.LoadoutProfile == loadoutProfile);

        var loadouts = await query
            .OrderBy(x => x.SortOrder)
            .ThenBy(x => x.LoadoutName)
            .ToListAsync();

        var slotsByLoadout = await GetSlotsByLoadoutIdsAsync(loadouts.Select(x => x.EquipmentLoadoutId));
        return loadouts.Select(x => MapLoadout(x, slotsByLoadout.GetValueOrDefault(x.EquipmentLoadoutId, []))).ToList();
    }

    public async Task<EquipmentLoadoutResponse?> GetByIdAsync(Guid loadoutId)
    {
        var loadout = await dbContext.EquipmentLoadouts
            .AsNoTracking()
            .FirstOrDefaultAsync(x => x.EquipmentLoadoutId == loadoutId && !x.IsDeleted);

        if (loadout is null)
            return null;

        var slots = await GetSlotEntitiesAsync(loadoutId);
        return MapLoadout(loadout, slots.Select(MapSlot).ToList());
    }

    public async Task<EquipmentLoadoutResponse> CreateAsync(EquipmentLoadoutCreateRequest request)
    {
        var now = DateTime.UtcNow;
        var entity = new EquipmentLoadoutEntity
        {
            EquipmentLoadoutId = Guid.NewGuid(),
            AccountId = request.AccountId,
            LoadoutProfile = request.LoadoutProfile,
            LoadoutName = request.LoadoutName,
            SortOrder = request.SortOrder,
            IsActive = false,
            MetadataJson = request.MetadataJson,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = request.CreatedBy,
            UpdatedBy = request.CreatedBy,
            IsDeleted = false,
        };

        if (request.IsActive == true)
        {
            await DeactivateOtherLoadoutsAsync(entity.AccountId, entity.LoadoutProfile, entity.EquipmentLoadoutId, request.CreatedBy, now);
            await dbContext.SaveChangesAsync();
        }

        entity.IsActive = request.IsActive == true;
        await dbContext.EquipmentLoadouts.AddAsync(entity);
        await dbContext.SaveChangesAsync();

        return MapLoadout(entity, []);
    }

    public async Task<EquipmentLoadoutResponse?> UpdateAsync(Guid loadoutId, EquipmentLoadoutUpdateRequest request)
    {
        var entity = await dbContext.EquipmentLoadouts
            .FirstOrDefaultAsync(x => x.EquipmentLoadoutId == loadoutId && !x.IsDeleted);

        if (entity is null)
            return null;

        if (!string.IsNullOrWhiteSpace(request.LoadoutProfile))
            entity.LoadoutProfile = request.LoadoutProfile;

        if (!string.IsNullOrWhiteSpace(request.LoadoutName))
            entity.LoadoutName = request.LoadoutName;

        if (request.SortOrder.HasValue)
            entity.SortOrder = request.SortOrder.Value;

        entity.MetadataJson = request.MetadataJson;
        entity.UpdatedAt = DateTime.UtcNow;
        entity.UpdatedBy = request.UpdatedBy;

        if (request.IsActive.HasValue)
        {
            if (request.IsActive.Value)
                await DeactivateOtherLoadoutsAsync(entity.AccountId, entity.LoadoutProfile, entity.EquipmentLoadoutId, request.UpdatedBy, entity.UpdatedAt);

            entity.IsActive = request.IsActive.Value;
        }

        await dbContext.SaveChangesAsync();

        var slots = await GetSlotEntitiesAsync(loadoutId);
        return MapLoadout(entity, slots.Select(MapSlot).ToList());
    }

    public async Task<bool> DeleteAsync(Guid loadoutId, Guid updatedBy)
    {
        var entity = await dbContext.EquipmentLoadouts
            .FirstOrDefaultAsync(x => x.EquipmentLoadoutId == loadoutId && !x.IsDeleted);

        if (entity is null)
            return false;

        var now = DateTime.UtcNow;
        entity.IsDeleted = true;
        entity.IsActive = false;
        entity.UpdatedAt = now;
        entity.UpdatedBy = updatedBy;

        var slots = await dbContext.EquipmentLoadoutSlots
            .Where(x => x.EquipmentLoadoutId == loadoutId && !x.IsDeleted)
            .ToListAsync();

        foreach (var slot in slots)
        {
            slot.IsDeleted = true;
            slot.UpdatedAt = now;
            slot.UpdatedBy = updatedBy;
        }

        await dbContext.SaveChangesAsync();
        return true;
    }

    public async Task<EquipmentLoadoutResponse?> ActivateAsync(Guid loadoutId, Guid updatedBy)
    {
        var entity = await dbContext.EquipmentLoadouts
            .FirstOrDefaultAsync(x => x.EquipmentLoadoutId == loadoutId && !x.IsDeleted);

        if (entity is null)
            return null;

        var now = DateTime.UtcNow;
        await DeactivateOtherLoadoutsAsync(entity.AccountId, entity.LoadoutProfile, entity.EquipmentLoadoutId, updatedBy, now);

        entity.IsActive = true;
        entity.UpdatedAt = now;
        entity.UpdatedBy = updatedBy;

        await dbContext.SaveChangesAsync();

        var slots = await GetSlotEntitiesAsync(loadoutId);
        return MapLoadout(entity, slots.Select(MapSlot).ToList());
    }

    public async Task<IReadOnlyList<EquipmentLoadoutSlotResponse>?> GetSlotsAsync(Guid loadoutId)
    {
        var loadoutExists = await dbContext.EquipmentLoadouts
            .AnyAsync(x => x.EquipmentLoadoutId == loadoutId && !x.IsDeleted);

        if (!loadoutExists)
            return null;

        var slots = await GetSlotEntitiesAsync(loadoutId);
        return slots.Select(MapSlot).ToList();
    }

    public async Task<EquipmentLoadoutSlotResponse?> UpsertSlotAsync(Guid loadoutId, EquipmentLoadoutSlotUpsertRequest request)
    {
        var loadout = await dbContext.EquipmentLoadouts
            .FirstOrDefaultAsync(x => x.EquipmentLoadoutId == loadoutId && !x.IsDeleted);

        if (loadout is null)
            return null;

        var equipmentExists = await dbContext.EquipmentInstances
            .AnyAsync(x => x.EquipmentInstanceId == request.EquipmentInstanceId
                && x.AccountId == loadout.AccountId
                && !x.IsDeleted);

        if (!equipmentExists)
            return null;

        var duplicateEquipment = await dbContext.EquipmentLoadoutSlots
            .FirstOrDefaultAsync(x => x.EquipmentLoadoutId == loadoutId
                && x.EquipmentInstanceId == request.EquipmentInstanceId
                && !x.IsDeleted
                && (x.SlotType != request.SlotType || x.SlotIndex != request.SlotIndex));

        if (duplicateEquipment is not null)
            return null;

        var now = DateTime.UtcNow;
        var entity = await dbContext.EquipmentLoadoutSlots
            .FirstOrDefaultAsync(x => x.EquipmentLoadoutId == loadoutId
                && x.SlotType == request.SlotType
                && x.SlotIndex == request.SlotIndex
                && !x.IsDeleted);

        if (entity is null)
        {
            entity = new EquipmentLoadoutSlotEntity
            {
                EquipmentLoadoutSlotId = Guid.NewGuid(),
                EquipmentLoadoutId = loadoutId,
                SlotType = request.SlotType,
                SlotIndex = request.SlotIndex,
                EquipmentInstanceId = request.EquipmentInstanceId,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = request.UpdatedBy,
                UpdatedBy = request.UpdatedBy,
                IsDeleted = false,
            };

            await dbContext.EquipmentLoadoutSlots.AddAsync(entity);
        }
        else
        {
            entity.EquipmentInstanceId = request.EquipmentInstanceId;
            entity.UpdatedAt = now;
            entity.UpdatedBy = request.UpdatedBy;
        }

        loadout.UpdatedAt = now;
        loadout.UpdatedBy = request.UpdatedBy;

        await dbContext.SaveChangesAsync();
        return MapSlot(entity);
    }

    public async Task<bool?> DeleteSlotAsync(Guid loadoutId, string slotType, int slotIndex, Guid updatedBy)
    {
        var loadout = await dbContext.EquipmentLoadouts
            .FirstOrDefaultAsync(x => x.EquipmentLoadoutId == loadoutId && !x.IsDeleted);

        if (loadout is null)
            return null;

        var slot = await dbContext.EquipmentLoadoutSlots
            .FirstOrDefaultAsync(x => x.EquipmentLoadoutId == loadoutId
                && x.SlotType == slotType
                && x.SlotIndex == slotIndex
                && !x.IsDeleted);

        if (slot is null)
            return false;

        var now = DateTime.UtcNow;
        slot.IsDeleted = true;
        slot.UpdatedAt = now;
        slot.UpdatedBy = updatedBy;
        loadout.UpdatedAt = now;
        loadout.UpdatedBy = updatedBy;

        await dbContext.SaveChangesAsync();
        return true;
    }

    private async Task<Dictionary<Guid, IReadOnlyList<EquipmentLoadoutSlotResponse>>> GetSlotsByLoadoutIdsAsync(IEnumerable<Guid> loadoutIds)
    {
        var ids = loadoutIds.ToArray();
        if (ids.Length == 0)
            return [];

        var slots = await dbContext.EquipmentLoadoutSlots
            .AsNoTracking()
            .Where(x => ids.Contains(x.EquipmentLoadoutId) && !x.IsDeleted)
            .OrderBy(x => x.SlotType)
            .ThenBy(x => x.SlotIndex)
            .ToListAsync();

        return slots
            .GroupBy(x => x.EquipmentLoadoutId)
            .ToDictionary(x => x.Key, x => (IReadOnlyList<EquipmentLoadoutSlotResponse>)x.Select(MapSlot).ToList());
    }

    private async Task<List<EquipmentLoadoutSlotEntity>> GetSlotEntitiesAsync(Guid loadoutId)
        => await dbContext.EquipmentLoadoutSlots
            .AsNoTracking()
            .Where(x => x.EquipmentLoadoutId == loadoutId && !x.IsDeleted)
            .OrderBy(x => x.SlotType)
            .ThenBy(x => x.SlotIndex)
            .ToListAsync();

    private async Task DeactivateOtherLoadoutsAsync(Guid accountId, string loadoutProfile, Guid activeLoadoutId, Guid updatedBy, DateTime updatedAt)
    {
        var activeLoadouts = await dbContext.EquipmentLoadouts
            .Where(x => x.AccountId == accountId
                && x.LoadoutProfile == loadoutProfile
                && x.EquipmentLoadoutId != activeLoadoutId
                && x.IsActive
                && !x.IsDeleted)
            .ToListAsync();

        foreach (var loadout in activeLoadouts)
        {
            loadout.IsActive = false;
            loadout.UpdatedAt = updatedAt;
            loadout.UpdatedBy = updatedBy;
        }
    }

    private static EquipmentLoadoutResponse MapLoadout(
        EquipmentLoadoutEntity entity,
        IReadOnlyList<EquipmentLoadoutSlotResponse> slots) => new()
    {
        EquipmentLoadoutId = entity.EquipmentLoadoutId,
        AccountId = entity.AccountId,
        LoadoutProfile = entity.LoadoutProfile,
        LoadoutName = entity.LoadoutName,
        SortOrder = entity.SortOrder,
        IsActive = entity.IsActive,
        MetadataJson = entity.MetadataJson,
        Slots = slots,
        CreatedAt = entity.CreatedAt,
        UpdatedAt = entity.UpdatedAt,
        CreatedBy = entity.CreatedBy,
        UpdatedBy = entity.UpdatedBy,
        IsDeleted = entity.IsDeleted,
    };

    private static EquipmentLoadoutSlotResponse MapSlot(EquipmentLoadoutSlotEntity entity) => new()
    {
        EquipmentLoadoutSlotId = entity.EquipmentLoadoutSlotId,
        EquipmentLoadoutId = entity.EquipmentLoadoutId,
        SlotType = entity.SlotType,
        SlotIndex = entity.SlotIndex,
        EquipmentInstanceId = entity.EquipmentInstanceId,
        CreatedAt = entity.CreatedAt,
        UpdatedAt = entity.UpdatedAt,
        CreatedBy = entity.CreatedBy,
        UpdatedBy = entity.UpdatedBy,
        IsDeleted = entity.IsDeleted,
    };
}
