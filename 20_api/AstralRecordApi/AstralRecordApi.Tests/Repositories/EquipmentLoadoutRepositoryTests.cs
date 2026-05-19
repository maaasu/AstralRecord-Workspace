using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class EquipmentLoadoutRepositoryTests
{
    [Fact]
    public async Task ActivateAsync_DeactivatesOtherLoadouts_InSameAccountProfile()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        await CreateLoadoutTablesAsync(options);

        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var activeLoadoutId = Guid.NewGuid();
        var targetLoadoutId = Guid.NewGuid();
        var now = DateTime.UtcNow;

        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await setupContext.EquipmentLoadouts.AddRangeAsync(
                new EquipmentLoadoutEntity
                {
                    EquipmentLoadoutId = activeLoadoutId,
                    AccountId = accountId,
                    LoadoutProfile = "GAME",
                    LoadoutName = "Normal",
                    SortOrder = 0,
                    IsActive = true,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = userId,
                    UpdatedBy = userId,
                    IsDeleted = false,
                },
                new EquipmentLoadoutEntity
                {
                    EquipmentLoadoutId = targetLoadoutId,
                    AccountId = accountId,
                    LoadoutProfile = "GAME",
                    LoadoutName = "Boss",
                    SortOrder = 1,
                    IsActive = false,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = userId,
                    UpdatedBy = userId,
                    IsDeleted = false,
                });

            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new EquipmentLoadoutRepository(dbContext);

        var activated = await repository.ActivateAsync(targetLoadoutId, userId);

        Assert.NotNull(activated);
        Assert.True(activated.IsActive);

        var loadouts = await dbContext.EquipmentLoadouts.AsNoTracking().ToListAsync();
        Assert.False(loadouts.Single(x => x.EquipmentLoadoutId == activeLoadoutId).IsActive);
        Assert.True(loadouts.Single(x => x.EquipmentLoadoutId == targetLoadoutId).IsActive);
    }

    [Fact]
    public async Task UpsertSlotAsync_RejectsEquipmentOwnedByAnotherAccount()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        await CreateLoadoutTablesAsync(options);
        await CreateEquipmentInstanceTableAsync(options);

        var accountId = Guid.NewGuid();
        var otherAccountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var loadoutId = Guid.NewGuid();
        var equipmentInstanceId = Guid.NewGuid();
        var now = DateTime.UtcNow;

        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await setupContext.EquipmentLoadouts.AddAsync(new EquipmentLoadoutEntity
            {
                EquipmentLoadoutId = loadoutId,
                AccountId = accountId,
                LoadoutProfile = "GAME",
                LoadoutName = "Normal",
                SortOrder = 0,
                IsActive = true,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = userId,
                UpdatedBy = userId,
                IsDeleted = false,
            });

            await setupContext.EquipmentInstances.AddAsync(new EquipmentInstanceEntity
            {
                EquipmentInstanceId = equipmentInstanceId,
                AccountId = otherAccountId,
                ItemId = "bronze_sword",
                EnhanceLevel = 0,
                RuneMaxSlots = 0,
                TranscendenceRank = 0,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = userId,
                UpdatedBy = userId,
                IsDeleted = false,
            });

            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new EquipmentLoadoutRepository(dbContext);

        var slot = await repository.UpsertSlotAsync(loadoutId, new EquipmentLoadoutSlotUpsertRequest
        {
            SlotType = "WEAPON",
            SlotIndex = 0,
            EquipmentInstanceId = equipmentInstanceId,
            UpdatedBy = userId,
        });

        Assert.Null(slot);
        Assert.Empty(await dbContext.EquipmentLoadoutSlots.AsNoTracking().ToListAsync());
    }

    private static async Task CreateLoadoutTablesAsync(DbContextOptions<AstralRecordDbContext> options)
    {
        await using var setupContext = new AstralRecordDbContext(options);

        await setupContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE equipment_loadout (
                equipment_loadout_id TEXT NOT NULL PRIMARY KEY,
                account_id TEXT NOT NULL,
                loadout_profile TEXT NOT NULL,
                loadout_name TEXT NOT NULL,
                sort_order INTEGER NOT NULL,
                is_active INTEGER NOT NULL,
                metadata_json TEXT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                is_deleted INTEGER NOT NULL
            );");

        await setupContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE equipment_loadout_slot (
                equipment_loadout_slot_id TEXT NOT NULL PRIMARY KEY,
                equipment_loadout_id TEXT NOT NULL,
                slot_type TEXT NOT NULL,
                slot_index INTEGER NOT NULL,
                equipment_instance_id TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                is_deleted INTEGER NOT NULL
            );");
    }

    private static async Task CreateEquipmentInstanceTableAsync(DbContextOptions<AstralRecordDbContext> options)
    {
        await using var setupContext = new AstralRecordDbContext(options);

        await setupContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE equipment_instance (
                equipment_instance_id TEXT NOT NULL PRIMARY KEY,
                account_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                enhance_level INTEGER NOT NULL,
                rune_max_slots INTEGER NOT NULL,
                transcendence_rank INTEGER NOT NULL,
                durability_max INTEGER NULL,
                durability_value INTEGER NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                is_deleted INTEGER NOT NULL
            );");
    }
}
