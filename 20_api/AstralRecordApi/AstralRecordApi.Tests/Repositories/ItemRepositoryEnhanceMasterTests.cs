using AstralRecordApi.Data;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Tests.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class ItemRepositoryEnhanceMasterTests
{
    [Theory]
    [InlineData("merian_charm", "MAGIC_ATTACK", "INTELLIGENCE")]
    [InlineData("balrog_charm", "RANGED_ATTACK", "DEXTERITY")]
    [InlineData("sauron_charm", "MELEE_ATTACK", "STRENGTH")]
    public async Task IluvatarSanctumCharms_PreserveFixedLevelEightStats(
        string itemId,
        string attackStatus,
        string secondaryStatus
    )
    {
        await using var dbContext = await CreateSeededMasterDataDbContextAsync();
        var repository = new ItemRepository(dbContext);

        var item = repository.GetById(itemId);

        Assert.NotNull(item?.Equipment);
        Assert.Equal("EPIC", item!.Rarity);
        Assert.Equal("ACCESSORY", item.Equipment!.Slot);
        Assert.Equal("CHARM", item.Equipment.Tag);
        Assert.Equal(8, item.Equipment.RequiredLevel);
        Assert.Collection(
            item.Equipment.Stats,
            stat =>
            {
                Assert.Equal(attackStatus, stat.Status);
                Assert.Equal("FLAT", stat.Type);
                Assert.Equal("10", stat.Value?.Min);
                Assert.Equal("10", stat.Value?.Max);
            },
            stat =>
            {
                Assert.Equal(secondaryStatus, stat.Status);
                Assert.Equal("FLAT", stat.Type);
                Assert.Equal("2", stat.Value?.Min);
                Assert.Equal("2", stat.Value?.Max);
            });
    }

    [Fact]
    public async Task GetById_FromInlineEnergyBakedPayload_PreservesConsumableUseTiming()
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

    [Theory]
    [InlineData("nox_sword", "MELEE_ATTACK")]
    [InlineData("nox_bow", "RANGED_ATTACK")]
    [InlineData("nox_staff", "MAGIC_ATTACK")]
    public async Task GetById_ReturnsDeterministicTenLevelEnhanceData_ForNoxWeapons(
        string itemId,
        string expectedStatus
    )
    {
        await using var dbContext = await CreateSeededMasterDataDbContextAsync();
        var repository = new ItemRepository(dbContext);

        var payloadJson = dbContext.Entries.Single(entry => entry.MasterId == itemId).PayloadJson;
        Assert.Contains("\"enhance\":", payloadJson, StringComparison.OrdinalIgnoreCase);

        var item = repository.GetById(itemId);

        Assert.NotNull(item);
        Assert.Equal("17", item!.Equipment!.Stats.Single(stat => stat.Status == expectedStatus).Value!.Min);
        var levels = item!.Equipment!.Enhance!.Levels;
        Assert.Equal(10, item.Equipment.Enhance.MaxLevel);
        Assert.Equal(10, levels.Count);
        Assert.Equal(Enumerable.Range(1, 10), levels.Select(level => level.Level));
        Assert.All(levels, level =>
        {
            Assert.Equal(1.0f, level.SuccessRate);
            Assert.Equal("NONE", level.FailAction);
            var stat = Assert.Single(level.StatIncrease);
            Assert.Equal(expectedStatus, stat.Status);
            Assert.Equal("FLAT", stat.Type);
            Assert.Equal("1", stat.Value);
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
        await MasterDataTestSeed.CreateSchemaAsync(dbContext);
        foreach (var fixture in MasterDataTestFixtures.EnhancementItems)
        {
            await MasterDataTestSeed.SeedInlinePayloadAsync(
                dbContext,
                fixture.Payload,
                "item",
                fixture.Category);
        }
        return dbContext;
    }
}
