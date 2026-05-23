using AstralRecordApi.Data;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using AstralRecordApi.Models;
using AstralRecordApi.Tests.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Xunit;

namespace AstralRecordApi.Tests.Services;

public class EquipmentServiceTests
{
    [Fact]
    public async Task EnchantAsync_SelectsMultipleStatuses_FromWeightedPool()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        var accountId = Guid.NewGuid();
        var actorId = Guid.NewGuid();

        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await CreateEquipmentTestSchemaAsync(setupContext);

            setupContext.Accounts.Add(new AstralRecordApi.Data.Entities.AccountEntity
            {
                Uuid = accountId,
                UserId = Guid.NewGuid(),
                AccountName = "tester",
                SlotIndex = 0,
                IsActive = true,
                Mode = 0,
                CreatedAt = DateTime.UtcNow,
                UpdatedAt = DateTime.UtcNow,
                CreatedBy = actorId,
                UpdatedBy = actorId,
                IsDeleted = false,
            });

            await setupContext.SaveChangesAsync();
            await setupContext.Database.ExecuteSqlRawAsync("PRAGMA foreign_keys = ON;");
        }

        var itemRepository = await CreateItemRepositoryFromFilebaseAsync();

        await using var dbContext = new AstralRecordDbContext(options);
        var equipmentRepository = new EquipmentRepository(dbContext);
        var accountRepository = new AccountRepository(dbContext);
        var runeRepository = new RuneRepository(dbContext);
        var service = new EquipmentService(itemRepository, equipmentRepository, runeRepository, accountRepository);

        var created = await service.CreateAsync(new EquipmentCreateRequest
        {
            EquipmentId = "sample_sword",
            AccountId = accountId,
            Source = "test",
            CreatedBy = actorId
        });

        Assert.NotNull(created);

        var observedStatuses = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        for (var i = 0; i < 200; i++)
        {
            var result = await service.EnchantAsync(new EquipmentEnchantRequest
            {
                EquipmentInstanceId = created!.EquipmentInstanceId,
                PoolIndex = 0,
                UpdatedBy = actorId,
            });

            Assert.NotNull(result);
            var latest = result!.Enchants.Single(x => x.PoolIndex == 0);
            observedStatuses.Add(latest.Status);
        }

        Assert.Contains("ATTACK", observedStatuses);
        Assert.True(observedStatuses.Count > 1, $"Expected multiple statuses from weighted pool, but got: {string.Join(", ", observedStatuses)}");
    }

    [Fact]
    public async Task CreateAsync_Succeeds_WithoutLegacyEnchantPoolTable()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        var accountId = Guid.NewGuid();
        var actorId = Guid.NewGuid();

        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await CreateEquipmentTestSchemaAsync(setupContext);

            setupContext.Accounts.Add(new AstralRecordApi.Data.Entities.AccountEntity
            {
                Uuid = accountId,
                UserId = Guid.NewGuid(),
                AccountName = "tester",
                SlotIndex = 0,
                IsActive = true,
                Mode = 0,
                CreatedAt = DateTime.UtcNow,
                UpdatedAt = DateTime.UtcNow,
                CreatedBy = actorId,
                UpdatedBy = actorId,
                IsDeleted = false,
            });

            await setupContext.SaveChangesAsync();
            await setupContext.Database.ExecuteSqlRawAsync("PRAGMA foreign_keys = ON;");
        }

        var itemRepository = await CreateItemRepositoryFromFilebaseAsync();

        await using var dbContext = new AstralRecordDbContext(options);
        var equipmentRepository = new EquipmentRepository(dbContext);
        var accountRepository = new AccountRepository(dbContext);
        var runeRepository = new RuneRepository(dbContext);
        var service = new EquipmentService(itemRepository, equipmentRepository, runeRepository, accountRepository);

        var result = await service.CreateAsync(new EquipmentCreateRequest
        {
            EquipmentId = "sample_sword",
            AccountId = accountId,
            Source = "test",
            CreatedBy = actorId
        });

        Assert.NotNull(result);
        Assert.Equal(5, result!.StatRolls.Count);
        Assert.Contains(result.StatRolls, x => x.Status == "ATTACK");
        Assert.Contains(result.StatRolls, x => x.Status == "STRENGTH" && x.Min == "6" && x.Max == "6");
        Assert.Contains(result.StatRolls, x => x.Status == "CRITICAL_RATE" && x.Min == "4" && x.Max == "8");
        Assert.Contains(result.StatRolls, x => x.Status == "ATTACK_SPEED" && x.Min == "0.03" && x.Max == "0.08");
        Assert.Contains(result.StatRolls, x => x.Status == "ACCURACY" && x.Min == "5" && x.Max == "9");
    }

    [Fact]
    public async Task CreateAsync_ReturnsNull_WhenAccountDoesNotExist()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await CreateEquipmentTestSchemaAsync(setupContext);
            await setupContext.Database.ExecuteSqlRawAsync(@"
                CREATE TABLE equipment_instance_enchant_pool (
                    enchant_pool_id TEXT NOT NULL PRIMARY KEY,
                    equipment_instance_id TEXT NOT NULL,
                    pool_index INTEGER NOT NULL,
                    recipe_id TEXT NULL,
                    required_material_item_id TEXT NULL,
                    required_material_amount INTEGER NOT NULL,
                    required_currency INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    created_by TEXT NOT NULL,
                    updated_by TEXT NOT NULL,
                    is_deleted INTEGER NOT NULL
                );");
            await setupContext.Database.ExecuteSqlRawAsync("PRAGMA foreign_keys = ON;");
        }

        var itemRepository = await CreateItemRepositoryFromFilebaseAsync();

        await using var dbContext = new AstralRecordDbContext(options);
        var equipmentRepository = new EquipmentRepository(dbContext);
        var accountRepository = new AccountRepository(dbContext);
        var runeRepository = new RuneRepository(dbContext);
        var service = new EquipmentService(itemRepository, equipmentRepository, runeRepository, accountRepository);

        var result = await service.CreateAsync(new EquipmentCreateRequest
        {
            EquipmentId = "sample_sword",
            AccountId = Guid.NewGuid(),
            Source = "test",
            CreatedBy = Guid.NewGuid()
        });

        Assert.Null(result);
    }

    private static async Task<ItemRepository> CreateItemRepositoryFromFilebaseAsync()
    {
        var masterDbConnection = new SqliteConnection("Data Source=:memory:");
        await masterDbConnection.OpenAsync();
        var masterOptions = new DbContextOptionsBuilder<MasterDataDbContext>()
            .UseSqlite(masterDbConnection)
            .Options;
        var masterContext = new MasterDataDbContext(masterOptions);
        await MasterDataTestSeed.CreateSchemaAsync(masterContext);
        await MasterDataTestSeed.SeedEntryAsync(
            masterContext,
            @"E:\AstralRecord-Workspace\50_filebase\10.features.item\equipment\v1.sample_sword.yml",
            masterType: "item",
            category: "equipment");
        return new ItemRepository(masterContext);
    }

    private static async Task CreateEquipmentTestSchemaAsync(AstralRecordDbContext setupContext)
    {
        await setupContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE account (
                uuid TEXT NOT NULL PRIMARY KEY,
                user_id TEXT NOT NULL,
                account_name TEXT NOT NULL,
                slot_index INTEGER NOT NULL,
                is_active INTEGER NOT NULL,
                mode INTEGER NOT NULL,
                menu_shortcuts_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                is_deleted INTEGER NOT NULL
            );");

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
                is_deleted INTEGER NOT NULL,
                FOREIGN KEY(account_id) REFERENCES account(uuid)
            );");

        await setupContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE equipment_instance_stat_roll (
                stat_roll_id TEXT NOT NULL PRIMARY KEY,
                equipment_instance_id TEXT NOT NULL,
                status TEXT NOT NULL,
                random_min TEXT NOT NULL,
                random_max TEXT NOT NULL,
                sort_order INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL
            );");

        await setupContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE equipment_instance_enchant (
                enchant_id TEXT NOT NULL PRIMARY KEY,
                equipment_instance_id TEXT NOT NULL,
                slot_index INTEGER NOT NULL,
                pool_index INTEGER NOT NULL,
                status TEXT NOT NULL,
                type TEXT NOT NULL,
                value TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                FOREIGN KEY(equipment_instance_id) REFERENCES equipment_instance(equipment_instance_id)
            );");

        await setupContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE equipment_instance_rune (
                rune_id TEXT NOT NULL PRIMARY KEY,
                equipment_instance_id TEXT NOT NULL,
                rune_instance_id TEXT NULL,
                slot_index INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL
            );");
    }
}
