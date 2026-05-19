using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class EquipmentRepository(AstralRecordDbContext dbContext) : IEquipmentRepository
{
    public async Task AddAsync(
        EquipmentInstanceEntity instance,
        IReadOnlyList<EquipmentInstanceStatRollEntity> statRolls,
        IReadOnlyList<EquipmentInstanceEnchantPoolEntity> enchantPools)
    {
        _ = enchantPools;

        await dbContext.EquipmentInstances.AddAsync(instance);

        if (statRolls.Count > 0)
            await dbContext.EquipmentInstanceStatRolls.AddRangeAsync(statRolls);

        await dbContext.SaveChangesAsync();
    }

    public async Task<EquipmentInstanceEntity?> FindInstanceAsync(Guid instanceId)
        => await dbContext.EquipmentInstances
            .AsNoTracking()
            .FirstOrDefaultAsync(x => x.EquipmentInstanceId == instanceId && !x.IsDeleted);

    public async Task<IReadOnlyList<EquipmentInstanceStatRollEntity>> FindStatRollsAsync(Guid instanceId)
        => await dbContext.EquipmentInstanceStatRolls
            .AsNoTracking()
            .Where(x => x.EquipmentInstanceId == instanceId)
            .OrderBy(x => x.SortOrder)
            .ToListAsync();

    public async Task<IReadOnlyList<EquipmentInstanceEnchantEntity>> FindEnchantsAsync(Guid instanceId)
        => await dbContext.EquipmentInstanceEnchants
            .AsNoTracking()
            .Where(x => x.EquipmentInstanceId == instanceId)
            .OrderBy(x => x.SlotIndex)
            .ToListAsync();

    public async Task<IReadOnlyList<EquipmentInstanceRuneEntity>> FindRunesAsync(Guid instanceId)
        => await dbContext.EquipmentInstanceRunes
            .AsNoTracking()
            .Where(x => x.EquipmentInstanceId == instanceId)
            .OrderBy(x => x.SlotIndex)
            .ToListAsync();

    public Task<IReadOnlyList<EquipmentInstanceEnchantPoolEntity>> FindEnchantPoolsAsync(Guid instanceId)
    {
        _ = instanceId;
        return Task.FromResult<IReadOnlyList<EquipmentInstanceEnchantPoolEntity>>([]);
    }

    public async Task ApplyEnchantAsync(EquipmentInstanceEntity instance, EquipmentInstanceEnchantEntity enchant, Guid? overwriteEnchantId)
    {
        UpdateTrackedEquipmentInstance(instance);

        if (overwriteEnchantId.HasValue)
        {
            var existing = await dbContext.EquipmentInstanceEnchants
                .FirstOrDefaultAsync(x => x.EnchantId == overwriteEnchantId.Value && x.EquipmentInstanceId == instance.EquipmentInstanceId);

            if (existing is not null)
            {
                existing.PoolIndex = enchant.PoolIndex;
                existing.Status = enchant.Status;
                existing.Type = enchant.Type;
                existing.Value = enchant.Value;
                existing.UpdatedAt = enchant.UpdatedAt;
                existing.UpdatedBy = enchant.UpdatedBy;
            }
            else
            {
                await dbContext.EquipmentInstanceEnchants.AddAsync(enchant);
            }
        }
        else
        {
            await dbContext.EquipmentInstanceEnchants.AddAsync(enchant);
        }

        await dbContext.SaveChangesAsync();
    }

    public async Task<bool> DeleteEnchantByPoolIndexAsync(Guid instanceId, int poolIndex)
    {
        var enchants = await dbContext.EquipmentInstanceEnchants
            .Where(x => x.EquipmentInstanceId == instanceId && x.PoolIndex == poolIndex)
            .ToListAsync();

        if (enchants.Count == 0)
            return false;

        dbContext.EquipmentInstanceEnchants.RemoveRange(enchants);
        await dbContext.SaveChangesAsync();
        return true;
    }

    public async Task UpsertRuneAsync(EquipmentInstanceEntity instance, EquipmentInstanceRuneEntity rune)
    {
        UpdateTrackedEquipmentInstance(instance);

        var existing = await dbContext.EquipmentInstanceRunes
            .FirstOrDefaultAsync(x => x.EquipmentInstanceId == rune.EquipmentInstanceId && x.SlotIndex == rune.SlotIndex);

        if (existing is null)
        {
            await dbContext.EquipmentInstanceRunes.AddAsync(rune);
        }
        else
        {
            existing.RuneInstanceId = rune.RuneInstanceId;
            existing.ItemId = rune.ItemId;
            existing.UpdatedAt = rune.UpdatedAt;
            existing.UpdatedBy = rune.UpdatedBy;
        }

        await dbContext.SaveChangesAsync();
    }

    public async Task<bool> DeleteRuneBySlotIndexAsync(Guid instanceId, int slotIndex)
    {
        var rune = await dbContext.EquipmentInstanceRunes
            .FirstOrDefaultAsync(x => x.EquipmentInstanceId == instanceId && x.SlotIndex == slotIndex);

        if (rune is null)
            return false;

        dbContext.EquipmentInstanceRunes.Remove(rune);
        await dbContext.SaveChangesAsync();
        return true;
    }

    public async Task UpdateInstanceAsync(EquipmentInstanceEntity instance)
    {
        UpdateTrackedEquipmentInstance(instance);
        await dbContext.SaveChangesAsync();
    }

    private void UpdateTrackedEquipmentInstance(EquipmentInstanceEntity instance)
    {
        var tracked = dbContext.EquipmentInstances.Local
            .FirstOrDefault(x => x.EquipmentInstanceId == instance.EquipmentInstanceId);

        if (tracked is null)
        {
            dbContext.EquipmentInstances.Attach(instance);
            dbContext.Entry(instance).State = EntityState.Modified;
            return;
        }

        dbContext.Entry(tracked).CurrentValues.SetValues(instance);
    }
}
