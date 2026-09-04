using System.Data;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.Data.SqlClient;
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
        var existing = await FindExistingInventoryAsync(request);
        if (existing is not null)
            return MapInventory(existing);

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
        try
        {
            await dbContext.SaveChangesAsync();
        }
        catch (DbUpdateException)
        {
            dbContext.Entry(entity).State = EntityState.Detached;
            existing = await FindExistingInventoryAsync(request);
            if (existing is not null)
                return MapInventory(existing);

            throw;
        }

        return MapInventory(entity);
    }

    private async Task<InventoryEntity?> FindExistingInventoryAsync(InventoryCreateRequest request)
    {
        return await dbContext.Inventories
            .AsNoTracking()
            .FirstOrDefaultAsync(x =>
                x.AccountId == request.AccountId
                && x.InventoryType == request.InventoryType
                && x.InventoryProfile == request.InventoryProfile
                && !x.IsDeleted);
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
        var inventory = await dbContext.Inventories
            .AsNoTracking()
            .FirstOrDefaultAsync(x => x.InventoryId == inventoryId && !x.IsDeleted);

        if (inventory is null)
            return null;

        var itemId = await ResolveEntryItemIdAsync(
            request.ItemId,
            request.InstanceType,
            request.InstanceId,
            inventory.AccountId);
        if (itemId is null)
            return null;

        var now = DateTime.UtcNow;
        var entity = new InventoryEntryEntity
        {
            InventoryEntryId = Guid.NewGuid(),
            InventoryId = inventoryId,
            SlotIndex = request.SlotIndex,
            ItemCategory = request.ItemCategory,
            ItemId = itemId,
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
        // DB の日時精度で確定した版を返し、次回の expectedUpdatedAt と一致させる。
        await dbContext.Entry(entity).ReloadAsync();

        return MapEntry(entity);
    }

    public async Task<InventoryEntryResponse?> UpdateEntryAsync(Guid inventoryEntryId, InventoryEntryUpdateRequest request)
    {
        var entity = await dbContext.InventoryEntries
            .FirstOrDefaultAsync(x => x.InventoryEntryId == inventoryEntryId && !x.IsDeleted);

        if (entity is null)
            return null;

        var ownerAccountId = await dbContext.Inventories
            .Where(inventory => inventory.InventoryId == entity.InventoryId && !inventory.IsDeleted)
            .Select(inventory => (Guid?)inventory.AccountId)
            .FirstOrDefaultAsync();
        if (!ownerAccountId.HasValue)
            return null;

        var itemId = await ResolveEntryItemIdAsync(
            request.ItemId,
            request.InstanceType,
            request.InstanceId,
            ownerAccountId.Value);
        if (itemId is null)
            return null;

        entity.SlotIndex = request.SlotIndex;
        entity.ItemCategory = request.ItemCategory;
        entity.ItemId = itemId;
        entity.InstanceType = request.InstanceType;
        entity.InstanceId = request.InstanceId;
        entity.Quantity = request.Quantity;
        entity.MetadataJson = request.MetadataJson;
        entity.UpdatedAt = DateTime.UtcNow;
        entity.UpdatedBy = request.UpdatedBy;

        await dbContext.SaveChangesAsync();
        await dbContext.Entry(entity).ReloadAsync();

        return MapEntry(entity);
    }

    public async Task<IReadOnlyList<InventoryEntryResponse>?> ReplaceEntriesAsync(
        Guid inventoryId,
        InventoryEntryReplaceRequest request
    )
    {
        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            var inventoryAccountId = await FindInventoryAccountIdAsync(inventoryId);
            if (!inventoryAccountId.HasValue)
                return null;

            await using var transaction = await dbContext.Database
                .BeginTransactionAsync(dbContext.Database.IsSqlServer()
                    ? IsolationLevel.ReadCommitted
                    : IsolationLevel.Serializable);

            if (!await LockAccountForInventoryUpdateAsync(inventoryAccountId.Value))
                return null;

            var inventory = await FindInventoryAndLockAccountInventoriesAsync(inventoryId, inventoryAccountId.Value);

            if (inventory is null)
                return null;

            var resolvedItemIds = new List<string?>(request.Entries.Count);
            foreach (var requested in request.Entries) {
                var itemId = await ResolveEntryItemIdAsync(
                    requested.ItemId,
                    requested.InstanceType,
                    requested.InstanceId,
                    inventory.AccountId);
                if (itemId is null)
                    return null;
                resolvedItemIds.Add(itemId);
            }

            var requestedIds = request.Entries
                .Where(entry => entry.InventoryEntryId.HasValue)
                .Select(entry => entry.InventoryEntryId!.Value)
                .ToArray();
            if (requestedIds.Distinct().Count() != requestedIds.Length)
                return null;

            var now = DateTime.UtcNow;
            var currentEntries = await FindCurrentEntriesForUpdateAsync(inventoryId);
            var knownEntries = requestedIds.Length == 0
                ? new Dictionary<Guid, InventoryEntryEntity>()
                : await FindEntriesForUpdateAsync(requestedIds);

            // 一度消費・削除されたentry UUIDはクライアントの古いスナップショットから復活させない。
            if (knownEntries.Values.Any(entry => entry.IsDeleted))
                return null;
            foreach (var requested in request.Entries.Where(entry => entry.InventoryEntryId.HasValue))
            {
                if (knownEntries.TryGetValue(requested.InventoryEntryId!.Value, out var known)
                    && (!requested.ExpectedUpdatedAt.HasValue
                        || known.UpdatedAt != requested.ExpectedUpdatedAt.Value))
                    return null;
            }

            if (knownEntries.Count > 0)
            {
                var knownInventoryIds = knownEntries.Values
                    .Select(entry => entry.InventoryId)
                    .Distinct()
                    .ToArray();
                var knownOwners = await dbContext.Inventories.AsNoTracking()
                    .Where(owner => knownInventoryIds.Contains(owner.InventoryId))
                    .Select(owner => new { owner.InventoryId, owner.AccountId, owner.IsDeleted })
                    .ToArrayAsync();
                if (knownOwners.Length != knownInventoryIds.Length
                    || knownOwners.Any(owner => owner.AccountId != inventory.AccountId || owner.IsDeleted))
                    return null;
            }

            // 部分一意インデックスへ途中配置が衝突しないよう、移動対象と現在配置を一度無効化する。
            var entriesToDisable = currentEntries
                .Concat(knownEntries.Values)
                .DistinctBy(entry => entry.InventoryEntryId)
                .ToArray();
            foreach (var entity in entriesToDisable)
            {
                entity.IsDeleted = true;
                entity.UpdatedAt = now;
                entity.UpdatedBy = request.UpdatedBy;
            }
            if (entriesToDisable.Length > 0)
                await dbContext.SaveChangesAsync();

            for (var entryIndex = 0; entryIndex < request.Entries.Count; entryIndex++)
            {
                var entry = request.Entries[entryIndex];
                InventoryEntryEntity entity;
                if (entry.InventoryEntryId.HasValue
                    && knownEntries.TryGetValue(entry.InventoryEntryId.Value, out var known))
                {
                    entity = known;
                }
                else
                {
                    entity = new InventoryEntryEntity
                    {
                        InventoryEntryId = entry.InventoryEntryId ?? Guid.NewGuid(),
                        CreatedAt = now,
                        CreatedBy = request.UpdatedBy,
                    };
                    await dbContext.InventoryEntries.AddAsync(entity);
                }

                entity.InventoryId = inventoryId;
                entity.SlotIndex = entry.SlotIndex;
                entity.ItemCategory = entry.ItemCategory;
                entity.ItemId = resolvedItemIds[entryIndex];
                entity.InstanceType = entry.InstanceType;
                entity.InstanceId = entry.InstanceId;
                entity.Quantity = entry.Quantity;
                entity.MetadataJson = entry.MetadataJson;
                entity.UpdatedAt = now;
                entity.UpdatedBy = request.UpdatedBy;
                entity.IsDeleted = false;
            }

            await dbContext.SaveChangesAsync();
            // datetime2(3) への保存で丸められるため、メモリ上の DateTime.UtcNow を版として返さない。
            // ロックを保持している transaction 内で一括再取得し、この保存で確定した応答を作る。
            var persistedEntries = await GetEntriesByInventoryIdAsync(inventoryId);
            await transaction.CommitAsync();

            return persistedEntries;
        });
    }

    private async Task<List<InventoryEntryEntity>> FindCurrentEntriesForUpdateAsync(Guid inventoryId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.InventoryEntries
                .FromSqlInterpolated($"""
                    SELECT *
                    FROM [dbo].[inventory_entry] WITH (UPDLOCK, HOLDLOCK)
                    WHERE [inventory_id] = {inventoryId} AND [is_deleted] = 0
                    ORDER BY [inventory_entry_id]
                    """)
                .ToListAsync();
        }

        return await dbContext.InventoryEntries
            .Where(entry => entry.InventoryId == inventoryId && !entry.IsDeleted)
            .OrderBy(entry => entry.InventoryEntryId)
            .ToListAsync();
    }

    private async Task<Guid?> FindInventoryAccountIdAsync(Guid inventoryId) => await dbContext.Inventories
        .AsNoTracking()
        .Where(inventory => inventory.InventoryId == inventoryId && !inventory.IsDeleted)
        .Select(inventory => (Guid?)inventory.AccountId)
        .FirstOrDefaultAsync();

    private async Task<bool> LockAccountForInventoryUpdateAsync(Guid accountId)
    {
        if (!dbContext.Database.IsSqlServer())
            return true;

        var account = await dbContext.Accounts
            .FromSqlInterpolated($"""
                SELECT TOP (1) *
                FROM [dbo].[account] WITH (UPDLOCK, HOLDLOCK)
                WHERE [uuid] = {accountId} AND [is_deleted] = 0
                """)
            .AsNoTracking()
            .SingleOrDefaultAsync();
        return account is not null;
    }

    private async Task<InventoryEntity?> FindInventoryAndLockAccountInventoriesAsync(
        Guid inventoryId,
        Guid accountId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            // A valid request may move entries between inventories of the same account.
            // Lock all active parent rows in one deterministic query before locking entry
            // rows. The target's account_id is read before the transaction so this query
            // cannot acquire a target shared lock before the ordered parent update locks.
            var accountInventories = await dbContext.Inventories
                .FromSqlInterpolated($"""
                    SELECT *
                    FROM [dbo].[inventory] WITH (UPDLOCK, HOLDLOCK)
                    WHERE [account_id] = {accountId} AND [is_deleted] = 0
                    ORDER BY [inventory_id]
                    """)
                .AsNoTracking()
                .ToListAsync();
            return accountInventories.SingleOrDefault(inventory => inventory.InventoryId == inventoryId);
        }

        return await dbContext.Inventories
            .AsNoTracking()
            .FirstOrDefaultAsync(x => x.InventoryId == inventoryId && !x.IsDeleted);
    }

    private async Task<Dictionary<Guid, InventoryEntryEntity>> FindEntriesForUpdateAsync(
        IReadOnlyCollection<Guid> entryIds)
    {
        var orderedIds = entryIds.OrderBy(entryId => entryId).ToArray();
        if (!dbContext.Database.IsSqlServer())
        {
            return await dbContext.InventoryEntries
                .Where(entry => orderedIds.Contains(entry.InventoryEntryId))
                .OrderBy(entry => entry.InventoryEntryId)
                .ToDictionaryAsync(entry => entry.InventoryEntryId);
        }

        var parameters = new object[orderedIds.Length];
        var placeholders = new string[orderedIds.Length];
        for (var index = 0; index < orderedIds.Length; index++)
        {
            var parameterName = $"@entryId{index}";
            placeholders[index] = parameterName;
            parameters[index] = new SqlParameter(parameterName, System.Data.SqlDbType.UniqueIdentifier)
            {
                Value = orderedIds[index],
            };
        }

        var sql = $"""
            SELECT *
            FROM [dbo].[inventory_entry] WITH (UPDLOCK, HOLDLOCK)
            WHERE [inventory_entry_id] IN ({string.Join(", ", placeholders)})
            ORDER BY [inventory_entry_id]
            """;
        return await dbContext.InventoryEntries
            .FromSqlRaw(sql, parameters)
            .ToDictionaryAsync(entry => entry.InventoryEntryId);
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

    public async Task<int> RepairEquipmentEntryItemIdsAsync(Guid accountId)
    {
        var candidates = await (
            from entry in dbContext.InventoryEntries
            join inventory in dbContext.Inventories on entry.InventoryId equals inventory.InventoryId
            join equipment in dbContext.EquipmentInstances on entry.InstanceId equals (Guid?)equipment.EquipmentInstanceId
            where !entry.IsDeleted
                && !inventory.IsDeleted
                && !equipment.IsDeleted
                && inventory.AccountId == accountId
                && equipment.AccountId == accountId
                && entry.ItemCategory == "equipment"
                && entry.InstanceType == "EQUIPMENT"
                && entry.Quantity == 1L
                && string.IsNullOrWhiteSpace(entry.ItemId)
                && !string.IsNullOrWhiteSpace(equipment.ItemId)
            select new { Entry = entry, EquipmentItemId = equipment.ItemId }
        ).ToListAsync();

        if (candidates.Count == 0)
            return 0;

        var now = DateTime.UtcNow;
        foreach (var candidate in candidates)
        {
            candidate.Entry.ItemId = candidate.EquipmentItemId;
            candidate.Entry.UpdatedAt = now;
            candidate.Entry.UpdatedBy = accountId;
        }

        await dbContext.SaveChangesAsync();
        return candidates.Count;
    }

    private async Task<string?> ResolveEntryItemIdAsync(
        string? requestedItemId,
        string? instanceType,
        Guid? instanceId,
        Guid ownerAccountId)
    {
        if (string.IsNullOrWhiteSpace(instanceType) || !instanceId.HasValue)
            return requestedItemId;

        var normalizedInstanceType = instanceType.Trim();
        string? authoritativeItemId = normalizedInstanceType.ToUpperInvariant() switch
        {
            "EQUIPMENT" => await dbContext.EquipmentInstances
                .Where(instance => instance.EquipmentInstanceId == instanceId.Value
                    && instance.AccountId == ownerAccountId
                    && !instance.IsDeleted)
                .Select(instance => instance.ItemId)
                .FirstOrDefaultAsync(),
            _ => null,
        };
        if (string.IsNullOrWhiteSpace(authoritativeItemId))
            return null;

        return string.IsNullOrWhiteSpace(requestedItemId)
            || string.Equals(requestedItemId, authoritativeItemId, StringComparison.OrdinalIgnoreCase)
            ? authoritativeItemId
            : null;
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
