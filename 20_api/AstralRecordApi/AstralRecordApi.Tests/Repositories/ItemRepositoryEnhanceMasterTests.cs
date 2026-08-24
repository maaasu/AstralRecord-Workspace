using AstralRecordApi.Data;
using AstralRecordApi.Options;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using System.Runtime.CompilerServices;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class ItemRepositoryEnhanceMasterTests
{
    [Fact]
    public async Task GetById_FromEnergyBakedYaml_PreservesConsumableUseTiming()
    {
        await using var dbContext = await CreateSeededMasterDataDbContextAsync();
        var repository = new ItemRepository(dbContext);

        var item = repository.GetById("energy_baked");

        Assert.NotNull(item?.Consumable?.OnUse);
        Assert.Equal(16, item!.Consumable!.OnUse!.UseTimeTicks);
        Assert.Equal(16, item.Consumable.OnUse.CooldownTicks);
        Assert.Equal("entity.generic.eat", item.Consumable.OnUse.UsingSound);
    }

    [Fact]
    public async Task OrbItems_AreReturnedByListAndDetailRepositories_WithOperationContracts()
    {
        await using var dbContext = await CreateSeededMasterDataDbContextAsync();
        var repository = new ItemRepository(dbContext);

        var allSummaries = repository.GetAllSummaries();
        var summaries = allSummaries
            .Where(item => item.Category == "orb")
            .ToArray();

        Assert.DoesNotContain(allSummaries, item => item.Category == "enhancement_material");

        Assert.Contains(summaries, item => item.Id == "tyr_orb");
        Assert.Contains(summaries, item => item.Id == "sindri_orb");
        Assert.Contains(summaries, item => item.Id == "transcendence_orb");
        Assert.Contains(summaries, item => item.Id == "enchant_fill_all_orb");

        foreach (var itemId in summaries.Select(item => item.Id))
        {
            var item = repository.GetById(itemId);

            Assert.NotNull(item);
            Assert.Equal("orb", item!.Category);
            Assert.NotNull(item.Orb?.Effect);
        }

        var weaponEnhance = repository.GetById("tyr_orb");
        var armorEnhance = repository.GetById("aegis_orb");
        var accessoryEnhance = repository.GetById("freya_orb");
        Assert.Equal("ENHANCE", weaponEnhance!.Orb!.Effect.Type);
        Assert.Equal(["WEAPON", "SUBWEAPON"], weaponEnhance.Orb.Effect.TargetSlots);
        Assert.Equal(["HEAD", "CHEST", "LEGS", "FEET"], armorEnhance!.Orb!.Effect.TargetSlots);
        Assert.Equal(["ACCESSORY"], accessoryEnhance!.Orb!.Effect.TargetSlots);

        var overwrite = repository.GetById("enchant_overwrite_orb");
        var fillOne = repository.GetById("enchant_fill_orb");
        var fillAll = repository.GetById("enchant_fill_all_orb");
        Assert.Equal("ENCHANT", fillAll!.Orb!.Effect.Type);
        Assert.Equal("enchant:enchant001", fillAll.Orb.Effect.EnchantMasterId);
        Assert.Equal("OVERWRITE_RANDOM", overwrite!.Orb!.Effect.EnchantOperation);
        Assert.Equal("FILL_ONE_EMPTY", fillOne!.Orb!.Effect.EnchantOperation);
        Assert.Equal("FILL_ALL_EMPTY", fillAll.Orb.Effect.EnchantOperation);

        var highRank = repository.GetById("high_tyr_orb");
        Assert.Equal(5, highRank!.Orb!.Effect.Rank);
        Assert.Equal("AT_MOST", highRank.Orb.Effect.RankMode);
        Assert.Contains("WEAPON", highRank.Orb.Effect.TargetSlots);

        var exactTransition = repository.GetById("transcendence_orb");
        var highTransition = repository.GetById("high_transcendence_orb");
        Assert.Equal(1, exactTransition!.Orb!.Effect.Rank);
        Assert.Equal("EXACT", exactTransition.Orb.Effect.RankMode);
        Assert.Equal(5, highTransition!.Orb!.Effect.Rank);
        Assert.Equal("AT_MOST", highTransition.Orb.Effect.RankMode);

        var sindriRepair = repository.GetById("sindri_orb");
        var fullRepair = repository.GetById("full_repair_orb");
        Assert.Equal(75, sindriRepair!.Orb!.Effect.RepairAmount);
        Assert.False(sindriRepair.Orb.Effect.RepairFull);
        Assert.True(fullRepair!.Orb!.Effect.RepairFull);
    }

    /// <summary>
    /// 設計入力: 40_filebase/10.features.item/orb/docs.orb.YAMLスキーマ定義.md
    /// 検証契約: すべてのエンチャントオーブは共通エンチャントマスタへの必須参照として Seeder に収集される。
    /// </summary>
    [Fact]
    public async Task Seeder_CollectsRequiredEnchantMasterReference_FromEveryEnchantOrb()
    {
        await using var dbContext = await CreateSeededMasterDataDbContextAsync();

        var references = await dbContext.References
            .Where(reference => reference.FromMasterType == "item"
                && reference.ReferenceType == "enchant"
                && reference.ReferenceIdValue == "enchant001")
            .OrderBy(reference => reference.FromMasterId)
            .ToArrayAsync();

        Assert.Equal(
            ["enchant_fill_all_orb", "enchant_fill_orb", "enchant_overwrite_orb"],
            references.Select(reference => reference.FromMasterId));
        Assert.All(references, reference =>
        {
            Assert.True(reference.IsRequired);
            Assert.Equal("$.orb.effect.enchantMasterId", reference.ReferencePath);
        });
    }

    [Fact]
    public async Task GetById_ReturnsEnhanceData_ForOrbEnhancementTargets()
    {
        await using var dbContext = await CreateSeededMasterDataDbContextAsync();
        var repository = new ItemRepository(dbContext);

        var expectations = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["nox_sword"] = "WEAPON",
            ["nox_armor_chest"] = "CHEST",
            ["nox_armor_boots"] = "FEET",
            ["nox_armor_helmet"] = "HEAD",
            ["nox_armor_legs"] = "LEGS",
        };

        foreach (var expectation in expectations)
        {
            var payloadJson = dbContext.Entries.Single(entry => entry.MasterId == expectation.Key).PayloadJson;
            Assert.Contains("\"enhance\":", payloadJson, StringComparison.OrdinalIgnoreCase);

            var item = repository.GetById(expectation.Key);

            Assert.NotNull(item);
            Assert.NotNull(item!.Equipment);
            Assert.Equal(expectation.Value, item.Equipment!.Slot);
            Assert.NotNull(item.Equipment.Enhance);
            Assert.Equal(item.Equipment.Enhance!.MaxLevel, item.Equipment.Enhance.Levels.Count);
            Assert.Equal(
                Enumerable.Range(1, item.Equipment.Enhance.MaxLevel),
                item.Equipment.Enhance.Levels.Select(level => level.Level));
            Assert.All(item.Equipment.Enhance.Levels, level => Assert.NotEmpty(level.StatIncrease));
        }
    }

    [Fact]
    public async Task GetById_ReturnsEnhanceData_ForNoxSword()
    {
        await using var dbContext = await CreateSeededMasterDataDbContextAsync();
        var repository = new ItemRepository(dbContext);

        var payloadJson = dbContext.Entries.Single(entry => entry.MasterId == "nox_sword").PayloadJson;
        Assert.Contains("\"enhance\":", payloadJson, StringComparison.OrdinalIgnoreCase);

        var item = repository.GetById("nox_sword");

        Assert.NotNull(item);
        var levels = item!.Equipment!.Enhance!.Levels;
        Assert.Equal(5, levels.Count);
        Assert.All(levels, level =>
        {
            Assert.Equal(1.0f, level.SuccessRate);
            Assert.Equal("NONE", level.FailAction);
        });
    }

    [Fact]
    public void DeserializeLiteralJson_PopulatesEnhanceData()
    {
        var payloadType = typeof(ItemRepository)
            .Assembly
            .GetType("AstralRecordApi.Repositories.MasterDataPayloadJson", throwOnError: true)!;
        var options = (System.Text.Json.JsonSerializerOptions)payloadType
            .GetField("Options", System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.Static)!
            .GetValue(null)!;

        var json = """
            {
              "schemaVersion": 1,
              "id": "bronze_sword",
              "category": "equipment",
              "name": "bronze",
              "icon": "IRON_SWORD",
              "rarity": "COMMON",
              "maxStack": 1,
              "equipment": {
                "slot": "WEAPON",
                "handType": "ONE",
                "requiredLevel": 0,
                "stats": [],
                "enhance": {
                  "maxLevel": 3,
                  "levels": [
                    {
                      "level": 1,
                      "statIncrease": [
                        {
                          "status": "ATTACK",
                          "type": "FLAT",
                          "value": 2
                        }
                      ],
                      "successRate": 0.5,
                      "failAction": "SET_LEVEL",
                      "failTargetLevel": 0
                    }
                  ]
                }
              }
            }
            """;

        var item = System.Text.Json.JsonSerializer.Deserialize<ItemResponse>(json, options);

        Assert.NotNull(item);
        Assert.NotNull(item!.Equipment);
        Assert.NotNull(item.Equipment!.Enhance);
        Assert.Equal(3, item.Equipment.Enhance!.MaxLevel);
        var level = Assert.Single(item.Equipment.Enhance.Levels);
        Assert.Equal(0.5f, level.SuccessRate);
        Assert.Equal("SET_LEVEL", level.FailAction);
        Assert.Equal(0, level.FailTargetLevel);
    }

    [Fact]
    public void DeserializeLiteralJson_PopulatesAppearance()
    {
        var payloadType = typeof(ItemRepository)
            .Assembly
            .GetType("AstralRecordApi.Repositories.MasterDataPayloadJson", throwOnError: true)!;
        var options = (System.Text.Json.JsonSerializerOptions)payloadType
            .GetField("Options", System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.Static)!
            .GetValue(null)!;

        var json = """
            {
              "schemaVersion": 1,
              "id": "starter_chestplate",
              "category": "equipment",
              "name": "starter chestplate",
              "icon": "LEATHER_CHESTPLATE",
              "rarity": "COMMON",
              "appearance": {
                "color": "#7A5A3A",
                "potionType": "HEALING"
              },
              "equipment": {
                "slot": "CHEST",
                "requiredLevel": 0,
                "stats": []
              }
            }
            """;

        var item = System.Text.Json.JsonSerializer.Deserialize<ItemResponse>(json, options);

        Assert.NotNull(item);
        Assert.NotNull(item!.Appearance);
        Assert.Equal("#7A5A3A", item.Appearance!.Color);
        Assert.Equal("HEALING", item.Appearance.PotionType);
    }

    [Fact]
    public void DeserializeLiteralJson_PopulatesEquipmentRequirementsAndTranscendenceRequirement()
    {
        var payloadType = typeof(ItemRepository)
            .Assembly
            .GetType("AstralRecordApi.Repositories.MasterDataPayloadJson", throwOnError: true)!;
        var options = (System.Text.Json.JsonSerializerOptions)payloadType
            .GetField("Options", System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.Static)!
            .GetValue(null)!;

        var json = """
            {
              "schemaVersion": 1,
              "id": "class_blade",
              "category": "equipment",
              "name": "class blade",
              "icon": "IRON_SWORD",
              "rarity": "COMMON",
              "maxStack": 1,
              "equipment": {
                "slot": "WEAPON",
                "requiredLevel": 5,
                "requiredClasses": [{"classId": "swordsman", "level": 3}],
                "transcendence": [{"rank": 1, "requiredEnhanceLevel": 5}]
              }
            }
            """;

        var item = System.Text.Json.JsonSerializer.Deserialize<ItemResponse>(json, options);

        Assert.NotNull(item?.Equipment);
        var requiredClass = Assert.Single(item!.Equipment!.RequiredClasses);
        Assert.Equal("swordsman", requiredClass.ClassId);
        Assert.Equal(3, requiredClass.Level);
        var transcendence = Assert.Single(item.Equipment.Transcendence);
        Assert.Equal(5, transcendence.RequiredEnhanceLevel);
    }

    private static async Task<MasterDataDbContext> CreateSeededMasterDataDbContextAsync()
    {
        var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<MasterDataDbContext>()
            .UseSqlite(connection)
            .Options;

        var dbContext = new MasterDataDbContext(options);
        await CreateMasterDataSchemaAsync(dbContext);

        var seeder = new MasterDataSeeder(
            dbContext,
            Microsoft.Extensions.Options.Options.Create(new FileDatabaseOptions
            {
                RootPath = Path.Combine(ResolveWorkspaceRoot(), "40_filebase"),
            }),
            Microsoft.Extensions.Options.Options.Create(new MasterDataOptions()),
            NullLogger<MasterDataSeeder>.Instance);

        await seeder.RunAsync(MasterDataSeedTrigger.Manual, MasterDataSeedMode.Rebuild);
        return dbContext;
    }

    private static async Task CreateMasterDataSchemaAsync(MasterDataDbContext dbContext)
    {
        await dbContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE master_data_source (
                source_id TEXT NOT NULL PRIMARY KEY,
                source_key TEXT NOT NULL,
                source_path TEXT NOT NULL,
                source_kind TEXT NOT NULL,
                schema_version INTEGER NOT NULL,
                is_enabled INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                is_deleted INTEGER NOT NULL
            );");

        await dbContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE master_data_entry (
                entry_id TEXT NOT NULL PRIMARY KEY,
                source_id TEXT NOT NULL,
                master_type TEXT NOT NULL,
                master_id TEXT NOT NULL,
                category TEXT NULL,
                type TEXT NULL,
                schema_version INTEGER NOT NULL,
                display_name TEXT NULL,
                source_file_path TEXT NOT NULL,
                source_file_hash TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                payload_version INTEGER NOT NULL,
                effective_from TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                is_deleted INTEGER NOT NULL
            );");

        await dbContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE master_data_reference (
                reference_id TEXT NOT NULL PRIMARY KEY,
                from_entry_id TEXT NOT NULL,
                from_master_type TEXT NOT NULL,
                from_master_id TEXT NOT NULL,
                reference_type TEXT NOT NULL,
                reference_id_value TEXT NOT NULL,
                reference_path TEXT NOT NULL,
                is_required INTEGER NOT NULL,
                sort_order INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                is_deleted INTEGER NOT NULL
            );");

        await dbContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE master_data_seed_run (
                seed_run_id TEXT NOT NULL PRIMARY KEY,
                trigger_type TEXT NOT NULL,
                status TEXT NOT NULL,
                source_root_path TEXT NOT NULL,
                started_at TEXT NOT NULL,
                finished_at TEXT NULL,
                file_count INTEGER NOT NULL DEFAULT 0,
                upserted_count INTEGER NOT NULL DEFAULT 0,
                deleted_count INTEGER NOT NULL DEFAULT 0,
                skipped_count INTEGER NOT NULL DEFAULT 0,
                error_message TEXT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL
            );");
    }

    private static string ResolveWorkspaceRoot([CallerFilePath] string currentFile = "")
    {
        var current = new FileInfo(currentFile).Directory;
        while (current is not null)
        {
            var filebasePath = Path.Combine(current.FullName, "40_filebase");
            var apiPath = Path.Combine(current.FullName, "20_api");
            if (Directory.Exists(filebasePath) && Directory.Exists(apiPath))
            {
                return current.FullName;
            }

            current = current.Parent;
        }

        throw new InvalidOperationException("workspace root could not be resolved from the test output path.");
    }
}
