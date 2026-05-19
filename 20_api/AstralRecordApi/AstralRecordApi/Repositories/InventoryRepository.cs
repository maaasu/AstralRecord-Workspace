using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class InventoryRepository(AstralRecordDbContext dbContext) : IInventoryRepository
{
    public async Task<IReadOnlyList<InventoryResponse>> GetByAccountIdAsync(Guid accountId)
    {
        var inventories = await dbContext.Inventories
            .AsNoTracking()
            .Where(x => x.AccountId == accountId && !x.IsDeleted)
            .OrderBy(x => x.InventoryType)
            .ToListAsync();

        return inventories.Select(MapInventory).ToList();
    }

    public async Task<InventoryResponse?> GetByIdAsync(Guid inventoryId)
    {
        var inventory = await dbContext.Inventories
            .AsNoTracking()
            .FirstOrDefaultAsync(x => x.InventoryId == inventoryId && !x.IsDeleted);

        return inventory is null ? null : MapInventory(inventory);
    }

    public async Task<InventoryResponse> CreateAsync(InventoryCreateRequest request)
    {
        var now = DateTime.UtcNow;
        var entity = new InventoryEntity
        {
            InventoryId = Guid.NewGuid(),
            AccountId = request.AccountId,
            InventoryType = request.InventoryType,
            InventoryProfile = request.InventoryProfile,
            SlotCapacity = request.SlotCapacity,
            IsEnabled = request.IsEnabled ?? true,
            MetadataJson = request.MetadataJson,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = request.CreatedBy,
            UpdatedBy = request.CreatedBy,
            IsDeleted = false,
        };

        await dbContext.Inventories.AddAsync(entity);
        await dbContext.SaveChangesAsync();

        return MapInventory(entity);
    }

    public async Task<InventoryResponse?> UpdateAsync(Guid inventoryId, InventoryUpdateRequest request)
    {
        var entity = await dbContext.Inventories
            .FirstOrDefaultAsync(x => x.InventoryId == inventoryId && !x.IsDeleted);

        if (entity is null)
            return null;

        entity.SlotCapacity = request.SlotCapacity;

        if (request.IsEnabled.HasValue)
            entity.IsEnabled = request.IsEnabled.Value;

        if (!string.IsNullOrWhiteSpace(request.InventoryProfile))
            entity.InventoryProfile = request.InventoryProfile;

        entity.MetadataJson = request.MetadataJson;
        entity.UpdatedAt = DateTime.UtcNow;
        entity.UpdatedBy = request.UpdatedBy;

        await dbContext.SaveChangesAsync();

        return MapInventory(entity);
    }

    public async Task<IReadOnlyList<InventoryEntryResponse>> GetEntriesByInventoryIdAsync(Guid inventoryId)
    {
        var entries = await dbContext.InventoryEntries
            .AsNoTracking()
            .Where(x => x.InventoryId == inventoryId && !x.IsDeleted)
            .OrderBy(x => x.SlotIndex.HasValue ? 0 : 1)
            .ThenBy(x => x.SlotIndex)
            .ThenBy(x => x.ItemId)
            .ToListAsync();

        return entries.Select(MapEntry).ToList();
    }

    public async Task<InventoryEntryResponse?> GetEntryByIdAsync(Guid inventoryEntryId)
    {
        var entry = await dbContext.InventoryEntries
            .AsNoTracking()
            .FirstOrDefaultAsync(x => x.InventoryEntryId == inventoryEntryId && !x.IsDeleted);

        return entry is null ? null : MapEntry(entry);
    }

    public async Task<InventoryEntryResponse?> CreateEntryAsync(Guid inventoryId, InventoryEntryCreateRequest request)
    {
        var inventoryExists = await dbContext.Inventories
            .AnyAsync(x => x.InventoryId == inventoryId && !x.IsDeleted);

        if (!inventoryExists)
            return null;

        var now = DateTime.UtcNow;
        var entity = new InventoryEntryEntity
        {
            InventoryEntryId = Guid.NewGuid(),
            InventoryId = inventoryId,
            SlotIndex = request.SlotIndex,
            ItemCategory = request.ItemCategory,
            ItemId = request.ItemId,
            InstanceType = request.InstanceType,
            InstanceId = request.InstanceId,
            Quantity = request.Quantity,
            MetadataJson = request.MetadataJson,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = request.CreatedBy,
            UpdatedBy = request.CreatedBy,
            IsDeleted = false,
        };

        await dbContext.InventoryEntries.AddAsync(entity);
        await dbContext.SaveChangesAsync();

        return MapEntry(entity);
    }

    public async Task<InventoryEntryResponse?> UpdateEntryAsync(Guid inventoryEntryId, InventoryEntryUpdateRequest request)
    {
        var entity = await dbContext.InventoryEntries
            .FirstOrDefaultAsync(x => x.InventoryEntryId == inventoryEntryId && !x.IsDeleted);

        if (entity is null)
            return null;

        entity.SlotIndex = request.SlotIndex;
        entity.ItemCategory = request.ItemCategory;
        entity.ItemId = request.ItemId;
        entity.InstanceType = request.InstanceType;
        entity.InstanceId = request.InstanceId;
        entity.Quantity = request.Quantity;
        entity.MetadataJson = request.MetadataJson;
        entity.UpdatedAt = DateTime.UtcNow;
        entity.UpdatedBy = request.UpdatedBy;

        await dbContext.SaveChangesAsync();

        return MapEntry(entity);
    }

    public async Task<bool?> DeleteEntryAsync(Guid inventoryEntryId, Guid updatedBy)
    {
        var entity = await dbContext.InventoryEntries
            .FirstOrDefaultAsync(x => x.InventoryEntryId == inventoryEntryId && !x.IsDeleted);

        if (entity is null)
            return null;

        entity.IsDeleted = true;
        entity.UpdatedAt = DateTime.UtcNow;
        entity.UpdatedBy = updatedBy;

        await dbContext.SaveChangesAsync();
        return true;
    }

    private static InventoryResponse MapInventory(InventoryEntity entity) => new()
    {
        InventoryId = entity.InventoryId,
        AccountId = entity.AccountId,
        InventoryType = entity.InventoryType,
        InventoryProfile = entity.InventoryProfile,
        SlotCapacity = entity.SlotCapacity,
        IsEnabled = entity.IsEnabled,
        MetadataJson = entity.MetadataJson,
        CreatedAt = entity.CreatedAt,
        UpdatedAt = entity.UpdatedAt,
        CreatedBy = entity.CreatedBy,
        UpdatedBy = entity.UpdatedBy,
        IsDeleted = entity.IsDeleted,
    };

    private static InventoryEntryResponse MapEntry(InventoryEntryEntity entity) => new()
    {
        InventoryEntryId = entity.InventoryEntryId,
        InventoryId = entity.InventoryId,
        SlotIndex = entity.SlotIndex,
        ItemCategory = entity.ItemCategory,
        ItemId = entity.ItemId,
        InstanceType = entity.InstanceType,
        InstanceId = entity.InstanceId,
        Quantity = entity.Quantity,
        MetadataJson = entity.MetadataJson,
        CreatedAt = entity.CreatedAt,
        UpdatedAt = entity.UpdatedAt,
        CreatedBy = entity.CreatedBy,
        UpdatedBy = entity.UpdatedBy,
        IsDeleted = entity.IsDeleted,
    };
}
