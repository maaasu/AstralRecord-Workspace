using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class InventoryRepositoryTests
{
    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.03-更新系.md
    /// 検証契約: 一括保存は既存entry UUIDを維持し、クライアント生成entry UUIDを初回登録後も正本にする。
    /// </summary>
    [Fact]
    public async Task ReplaceEntriesAsync_PreservesStableEntryIds()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;
        await using var dbContext = new AstralRecordDbContext(options);
        await CreateSchemaAsync(dbContext);

        var accountId = Guid.NewGuid();
        var inventoryId = Guid.NewGuid();
        var existingEntryId = Guid.NewGuid();
        var clientGeneratedEntryId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        dbContext.Inventories.Add(new InventoryEntity
        {
            InventoryId = inventoryId,
            AccountId = accountId,
            InventoryType = "BAG",
            InventoryProfile = "GAME",
            IsEnabled = true,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = accountId,
            UpdatedBy = accountId,
        });
        dbContext.InventoryEntries.Add(new InventoryEntryEntity
        {
            InventoryEntryId = existingEntryId,
            InventoryId = inventoryId,
            SlotIndex = 1,
            ItemCategory = "skill_gem",
            ItemId = "00_skill_gem_mage_fireball",
            Quantity = 1,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = accountId,
            UpdatedBy = accountId,
        });
        await dbContext.SaveChangesAsync();

        var repository = new InventoryRepository(dbContext);
        var saved = await repository.ReplaceEntriesAsync(inventoryId, new InventoryEntryReplaceRequest
        {
            UpdatedBy = accountId,
            Entries =
            [
                new InventoryEntryReplaceItemRequest
                {
                    InventoryEntryId = existingEntryId,
                    ExpectedUpdatedAt = now,
                    SlotIndex = 2,
                    ItemCategory = "skill_gem",
                    ItemId = "00_skill_gem_mage_fireball",
                    Quantity = 1,
                },
                new InventoryEntryReplaceItemRequest
                {
                    InventoryEntryId = clientGeneratedEntryId,
                    SlotIndex = 3,
                    ItemCategory = "sigil",
                    ItemId = "cooldown_sigil",
                    Quantity = 2,
                },
            ],
        });

        Assert.NotNull(saved);
        var expectedIds = new[] { existingEntryId, clientGeneratedEntryId }.Order().ToArray();
        Assert.Equal(expectedIds, saved.Select(entry => entry.InventoryEntryId).Order().ToArray());
        var persisted = await dbContext.InventoryEntries.AsNoTracking()
            .Where(entry => !entry.IsDeleted)
            .OrderBy(entry => entry.InventoryEntryId)
            .ToArrayAsync();
        Assert.Equal(expectedIds, persisted.Select(entry => entry.InventoryEntryId).ToArray());
        Assert.Equal(2, persisted.Single(entry => entry.InventoryEntryId == existingEntryId).SlotIndex);
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.03-更新系.md
    /// 検証契約: stable UUIDを維持した2アイテムのスロット交換は、active slot一意制約へ途中衝突しない。
    /// </summary>
    [Fact]
    public async Task ReplaceEntriesAsync_SwapsSlotsWithoutUniqueConstraintConflict()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;
        await using var dbContext = new AstralRecordDbContext(options);
        await CreateSchemaAsync(dbContext);

        var accountId = Guid.NewGuid();
        var inventoryId = Guid.NewGuid();
        var firstId = Guid.NewGuid();
        var secondId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        dbContext.Inventories.Add(CreateInventory(inventoryId, accountId, now));
        dbContext.InventoryEntries.AddRange(
            CreateEntry(firstId, inventoryId, 1, "first", accountId, now),
            CreateEntry(secondId, inventoryId, 2, "second", accountId, now));
        await dbContext.SaveChangesAsync();

        var saved = await new InventoryRepository(dbContext).ReplaceEntriesAsync(
            inventoryId,
            new InventoryEntryReplaceRequest
            {
                UpdatedBy = accountId,
                Entries =
                [
                    new InventoryEntryReplaceItemRequest
                    {
                        InventoryEntryId = firstId,
                        ExpectedUpdatedAt = now,
                        SlotIndex = 2,
                        ItemCategory = "material",
                        ItemId = "first",
                        Quantity = 1,
                    },
                    new InventoryEntryReplaceItemRequest
                    {
                        InventoryEntryId = secondId,
                        ExpectedUpdatedAt = now,
                        SlotIndex = 1,
                        ItemCategory = "material",
                        ItemId = "second",
                        Quantity = 1,
                    },
                ],
            });

        Assert.NotNull(saved);
        Assert.Equal(2, saved.Single(entry => entry.InventoryEntryId == firstId).SlotIndex);
        Assert.Equal(1, saved.Single(entry => entry.InventoryEntryId == secondId).SlotIndex);
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様/11_3.03-習得済みスキル.md
    /// 検証契約: 素材消費で論理削除されたentry UUIDは古い一括保存要求から復活できない。
    /// </summary>
    [Fact]
    public async Task ReplaceEntriesAsync_RejectsPreviouslyDeletedEntryId()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;
        await using var dbContext = new AstralRecordDbContext(options);
        await CreateSchemaAsync(dbContext);

        var accountId = Guid.NewGuid();
        var inventoryId = Guid.NewGuid();
        var consumedEntryId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        dbContext.Inventories.Add(CreateInventory(inventoryId, accountId, now));
        var consumed = CreateEntry(
            consumedEntryId,
            inventoryId,
            1,
            "00_skill_gem_mage_fireball",
            accountId,
            now);
        consumed.ItemCategory = "skill_gem";
        consumed.IsDeleted = true;
        dbContext.InventoryEntries.Add(consumed);
        await dbContext.SaveChangesAsync();

        var saved = await new InventoryRepository(dbContext).ReplaceEntriesAsync(
            inventoryId,
            new InventoryEntryReplaceRequest
            {
                UpdatedBy = accountId,
                Entries =
                [
                    new InventoryEntryReplaceItemRequest
                    {
                        InventoryEntryId = consumedEntryId,
                        ExpectedUpdatedAt = now,
                        SlotIndex = 1,
                        ItemCategory = "skill_gem",
                        ItemId = "00_skill_gem_mage_fireball",
                        Quantity = 1,
                    },
                ],
            });

        Assert.Null(saved);
        Assert.True((await dbContext.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == consumedEntryId)).IsDeleted);
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.03-更新系.md
    /// 検証契約: 素材mutation後の古い一括保存は更新版不一致で拒否し、消費後数量を上書きしない。
    /// </summary>
    [Fact]
    public async Task ReplaceEntriesAsync_RejectsStaleUpdatedAt()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;
        await using var dbContext = new AstralRecordDbContext(options);
        await CreateSchemaAsync(dbContext);

        var accountId = Guid.NewGuid();
        var inventoryId = Guid.NewGuid();
        var entryId = Guid.NewGuid();
        var currentVersion = DateTime.UtcNow;
        dbContext.Inventories.Add(CreateInventory(inventoryId, accountId, currentVersion));
        var current = CreateEntry(entryId, inventoryId, 1, "cooldown_sigil", accountId, currentVersion);
        current.ItemCategory = "sigil";
        current.Quantity = 1;
        dbContext.InventoryEntries.Add(current);
        await dbContext.SaveChangesAsync();

        var saved = await new InventoryRepository(dbContext).ReplaceEntriesAsync(
            inventoryId,
            new InventoryEntryReplaceRequest
            {
                UpdatedBy = accountId,
                Entries =
                [
                    new InventoryEntryReplaceItemRequest
                    {
                        InventoryEntryId = entryId,
                        ExpectedUpdatedAt = currentVersion.AddSeconds(-1),
                        SlotIndex = 1,
                        ItemCategory = "sigil",
                        ItemId = "cooldown_sigil",
                        Quantity = 2,
                    },
                ],
            });

        Assert.Null(saved);
        var persisted = await dbContext.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == entryId);
        Assert.Equal(1, persisted.Quantity);
        Assert.Equal(currentVersion, persisted.UpdatedAt);
        Assert.False(persisted.IsDeleted);
    }

    private static InventoryEntity CreateInventory(Guid inventoryId, Guid accountId, DateTime now) => new()
    {
        InventoryId = inventoryId,
        AccountId = accountId,
        InventoryType = "BAG",
        InventoryProfile = "GAME",
        IsEnabled = true,
        CreatedAt = now,
        UpdatedAt = now,
        CreatedBy = accountId,
        UpdatedBy = accountId,
    };

    private static InventoryEntryEntity CreateEntry(
        Guid entryId,
        Guid inventoryId,
        int slotIndex,
        string itemId,
        Guid accountId,
        DateTime now) => new()
    {
        InventoryEntryId = entryId,
        InventoryId = inventoryId,
        SlotIndex = slotIndex,
        ItemCategory = "material",
        ItemId = itemId,
        Quantity = 1,
        CreatedAt = now,
        UpdatedAt = now,
        CreatedBy = accountId,
        UpdatedBy = accountId,
    };

    private static async Task CreateSchemaAsync(AstralRecordDbContext dbContext)
    {
        await dbContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE inventory (
                inventory_id TEXT NOT NULL PRIMARY KEY,
                account_id TEXT NOT NULL,
                inventory_type TEXT NOT NULL,
                inventory_profile TEXT NOT NULL,
                slot_capacity INTEGER NULL,
                is_enabled INTEGER NOT NULL,
                metadata_json TEXT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                is_deleted INTEGER NOT NULL
            );

            CREATE TABLE inventory_entry (
                inventory_entry_id TEXT NOT NULL PRIMARY KEY,
                inventory_id TEXT NOT NULL,
                slot_index INTEGER NULL,
                item_category TEXT NOT NULL,
                item_id TEXT NULL,
                instance_type TEXT NULL,
                instance_id TEXT NULL,
                quantity INTEGER NOT NULL,
                metadata_json TEXT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                is_deleted INTEGER NOT NULL
            );

            CREATE UNIQUE INDEX ux_inventory_entry_slot_active
                ON inventory_entry (inventory_id, slot_index)
                WHERE is_deleted = 0 AND slot_index IS NOT NULL;");
    }
}
