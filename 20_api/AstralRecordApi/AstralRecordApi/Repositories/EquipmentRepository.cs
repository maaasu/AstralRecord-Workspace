using System.Data;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class EquipmentRepository(AstralRecordDbContext dbContext) : IEquipmentRepository
{
    public async Task AddAsync(
        EquipmentInstanceEntity instance,
        IReadOnlyList<EquipmentInstanceStatRollEntity> statRolls)
    {
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

    public async Task<bool> DeleteEnchantBySlotIndexAsync(Guid instanceId, int slotIndex, Guid accountId)
    {
        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            var live = await FindInstanceForUpdateAsync(instanceId);
            if (live is null || live.IsDeleted || live.AccountId != accountId)
                return false;

            var enchants = await dbContext.EquipmentInstanceEnchants
                .Where(x => x.EquipmentInstanceId == instanceId && x.SlotIndex == slotIndex)
                .ToListAsync();
            if (enchants.Count == 0)
                return false;

            dbContext.EquipmentInstanceEnchants.RemoveRange(enchants);
            await dbContext.SaveChangesAsync();
            await transaction.CommitAsync();
            return true;
        });
    }

    public async Task<bool> UpsertRuneAsync(
        Guid instanceId,
        Guid accountId,
        EquipmentInstanceRuneEntity rune)
    {
        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            var live = await FindInstanceForUpdateAsync(instanceId);
            if (live is null || live.IsDeleted || live.AccountId != accountId)
                return false;

            live.UpdatedAt = rune.UpdatedAt;
            live.UpdatedBy = rune.UpdatedBy;
            var existing = await dbContext.EquipmentInstanceRunes
                .FirstOrDefaultAsync(x => x.EquipmentInstanceId == instanceId && x.SlotIndex == rune.SlotIndex);

            if (existing is null)
            {
                await dbContext.EquipmentInstanceRunes.AddAsync(rune);
            }
            else
            {
                existing.ItemId = rune.ItemId;
                existing.UpdatedAt = rune.UpdatedAt;
                existing.UpdatedBy = rune.UpdatedBy;
            }

            await dbContext.SaveChangesAsync();
            await transaction.CommitAsync();
            return true;
        });
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

    public async Task<EquipmentInstanceEntity?> UpdateDurabilityAsync(
        Guid instanceId,
        int durabilityValue,
        Guid updatedBy)
    {
        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            var live = await FindInstanceForUpdateAsync(instanceId);
            if (live is null
                || live.IsDeleted
                || live.AccountId != updatedBy
                || !live.DurabilityMax.HasValue
                || !live.DurabilityValue.HasValue)
                return null;

            live.DurabilityValue = Math.Clamp(durabilityValue, 0, live.DurabilityMax.Value);
            live.UpdatedAt = DateTime.UtcNow;
            live.UpdatedBy = updatedBy;
            await dbContext.SaveChangesAsync();
            await transaction.CommitAsync();
            return live;
        });
    }

    public async Task<bool> SoftDeleteInstanceAsync(Guid instanceId)
    {
        var instance = await dbContext.EquipmentInstances
            .FirstOrDefaultAsync(x => x.EquipmentInstanceId == instanceId && !x.IsDeleted);

        if (instance is null)
            return false;

        instance.IsDeleted = true;
        await dbContext.SaveChangesAsync();
        return true;
    }

    private async Task<EquipmentInstanceEntity?> FindInstanceForUpdateAsync(Guid instanceId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.EquipmentInstances
                .FromSqlInterpolated($"""
                    SELECT * FROM [dbo].[equipment_instance] WITH (UPDLOCK, HOLDLOCK)
                    WHERE [equipment_instance_id] = {instanceId}
                    """)
                .SingleOrDefaultAsync();
        }
        return await dbContext.EquipmentInstances
            .SingleOrDefaultAsync(instance => instance.EquipmentInstanceId == instanceId);
    }
}
