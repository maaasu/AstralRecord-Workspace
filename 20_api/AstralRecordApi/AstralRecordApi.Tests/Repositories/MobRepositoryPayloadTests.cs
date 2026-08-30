using AstralRecordApi.Data;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Tests.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Text.Json;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class MobRepositoryPayloadTests
{
    [Fact]
    public async Task GetById_FromAinurindaleYaml_PreservesFangWaveAndRareCharms()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<MasterDataDbContext>()
            .UseSqlite(connection)
            .Options;

        await using (var setupContext = new MasterDataDbContext(options))
        {
            await MasterDataTestSeed.CreateSchemaAsync(setupContext);
            await MasterDataTestSeed.SeedEntryAsync(
                setupContext,
                Path.Combine(
                    ResolveWorkspaceRoot(),
                    "40_filebase",
                    "40.features.mob",
                    "enemy",
                    "v1.ainurindale.yml"),
                "mob.enemy",
                "ENEMY");
        }

        await using var dbContext = new MasterDataDbContext(options);
        var repository = new MobRepository(dbContext);

        var mob = repository.GetById("ainurindale");

        Assert.Equal(8, mob?.Level);
        var combat = Assert.IsType<MobCombatResponse>(mob?.Ai?.Combat);
        var skill = Assert.Single(combat.Skills);
        Assert.Equal("mob_ainurindale_fang_wave", skill.Id);
        Assert.Equal(0.45D, skill.Params["damageRatio"]);
        Assert.Equal(8D, skill.Params["waveIntervalTicks"]);

        Assert.Equal("loot_table:normal_enemy_common_table_tier_1", mob?.Drops?.LootTable);
        Assert.Collection(
            mob!.Drops!.Items,
            item => AssertRareCharm(item, "merian_charm"),
            item => AssertRareCharm(item, "balrog_charm"),
            item => AssertRareCharm(item, "sauron_charm"));
    }

    [Fact]
    public async Task GetById_FromShieldGuardYaml_PreservesMissingRechargeAmount()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<MasterDataDbContext>()
            .UseSqlite(connection)
            .Options;

        await using (var setupContext = new MasterDataDbContext(options))
        {
            await MasterDataTestSeed.CreateSchemaAsync(setupContext);
            await MasterDataTestSeed.SeedEntryAsync(
                setupContext,
                Path.Combine(
                    ResolveWorkspaceRoot(),
                    "40_filebase",
                    "40.features.mob",
                    "enemy",
                    "v1.midgard_shield_guard.yml"),
                "mob.enemy",
                "ENEMY");
        }

        await using var dbContext = new MasterDataDbContext(options);
        var repository = new MobRepository(dbContext);

        var mob = repository.GetById("midgard_shield_guard");

        Assert.NotNull(mob?.Shield);
        Assert.True(mob!.Shield!.Enabled);
        Assert.Equal(4.0D, mob.Shield.Max);
        Assert.Equal(15.0D, mob.Shield.RechargeTimeSeconds);
        Assert.Null(mob.Shield.RechargeAmount);
    }

    [Fact]
    public async Task GetById_FromSkeletonArcherYaml_DoesNotAddNormalAttack_AndPreservesSkillBinding()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<MasterDataDbContext>()
            .UseSqlite(connection)
            .Options;

        await using (var setupContext = new MasterDataDbContext(options))
        {
            await MasterDataTestSeed.CreateSchemaAsync(setupContext);
            await MasterDataTestSeed.SeedEntryAsync(
                setupContext,
                Path.Combine(
                    ResolveWorkspaceRoot(),
                    "40_filebase",
                    "40.features.mob",
                    "enemy",
                    "v1.midgard_skeleton_archer.yml"),
                "mob.enemy",
                "ENEMY");
        }

        await using var dbContext = new MasterDataDbContext(options);
        var repository = new MobRepository(dbContext);

        var mob = repository.GetById("midgard_skeleton_archer");

        var combat = Assert.IsType<MobCombatResponse>(mob?.Ai?.Combat);
        Assert.Null(combat.NormalAttack);
        Assert.Null(combat.AttackIntervalTicks);
        var skill = Assert.Single(combat.Skills);
        Assert.Equal("mob_skeleton_bow_shot", skill.Id);
        Assert.Equal(16D, skill.ActivationRange);
        Assert.Equal(36L, skill.CooldownTicks);
        Assert.Equal(12L, skill.CastTimeTicks);
        Assert.Equal(0.85D, skill.Params["damageRatio"]);

        using var responseJson = JsonDocument.Parse(JsonSerializer.Serialize(
            mob,
            new JsonSerializerOptions(JsonSerializerDefaults.Web)));
        var responseCombat = responseJson.RootElement.GetProperty("ai").GetProperty("combat");
        Assert.False(responseCombat.TryGetProperty("normalAttack", out _));
        Assert.False(responseCombat.TryGetProperty("attackIntervalTicks", out _));
        Assert.Equal("mob_skeleton_bow_shot", responseCombat.GetProperty("skills")[0].GetProperty("id").GetString());
    }

    [Fact]
    public void DeserializeLiteralJson_PreservesExplicitNormalAttack_AndLegacySkillId()
    {
        var mob = JsonSerializer.Deserialize<MobResponse>("""
            {
              "schemaVersion": 1,
              "id": "legacy_combat_mob",
              "type": "MOB",
              "category": "ENEMY",
              "name": "legacy combat mob",
              "level": 1,
              "entityType": "ZOMBIE",
              "baseStats": [],
              "ai": {
                "combat": {
                  "style": "MELEE",
                  "normalAttack": { "range": 2.5, "intervalTicks": 24 },
                  "attackIntervalTicks": 30,
                  "skills": ["mob_legacy_attack"]
                }
              }
            }
            """, MasterDataJsonOptions());

        var combat = Assert.IsType<MobCombatResponse>(mob?.Ai?.Combat);
        Assert.Equal(2.5D, combat.NormalAttack?.Range);
        Assert.Equal(24L, combat.NormalAttack?.IntervalTicks);
        Assert.Equal(30L, combat.AttackIntervalTicks);
        Assert.Equal("mob_legacy_attack", Assert.Single(combat.Skills).Id);

        using var responseJson = JsonDocument.Parse(JsonSerializer.Serialize(
            mob,
            new JsonSerializerOptions(JsonSerializerDefaults.Web)));
        var responseCombat = responseJson.RootElement.GetProperty("ai").GetProperty("combat");
        Assert.Equal(2.5D, responseCombat.GetProperty("normalAttack").GetProperty("range").GetDouble());
        Assert.Equal(24L, responseCombat.GetProperty("normalAttack").GetProperty("intervalTicks").GetInt64());
        Assert.Equal(30L, responseCombat.GetProperty("attackIntervalTicks").GetInt64());
        Assert.Equal("mob_legacy_attack", responseCombat.GetProperty("skills")[0].GetProperty("id").GetString());
    }

    [Fact]
    public void DeserializeLiteralJson_PopulatesShield()
    {
        var json = """
            {
              "schemaVersion": 1,
              "id": "goblin_warrior",
              "type": "MOB",
              "category": "ENEMY",
              "name": "goblin warrior",
              "level": 5,
              "entityType": "ZOMBIE",
              "baseStats": [
                { "status": "MAX_HEALTH", "value": 100 }
              ],
              "shield": {
                "enabled": true,
                "max": 10,
                "rechargeTimeSeconds": 15,
                "rechargeAmount": 25
              },
              "ai": {
                "idle": { "behavior": "WANDER" },
                "targeting": { "strategy": "NEAREST", "aggroRange": 12, "retaliateOnly": true },
                "combat": { "style": "MELEE" }
              }
            }
            """;

        var mob = JsonSerializer.Deserialize<MobResponse>(json, MasterDataJsonOptions());

        Assert.NotNull(mob);
        Assert.NotNull(mob!.Shield);
        Assert.True(mob.Shield!.Enabled);
        Assert.Equal(10.0D, mob.Shield.Max);
        Assert.Equal(15.0D, mob.Shield.RechargeTimeSeconds);
        Assert.Equal(25.0D, mob.Shield.RechargeAmount);
        Assert.NotNull(mob.Ai?.Targeting);
        Assert.True(mob.Ai.Targeting.RetaliateOnly);
    }

    [Fact]
    public void DeserializeLiteralJson_PopulatesBossChallenge()
    {
        var json = """
            {
              "schemaVersion": 1,
              "id": "twilight_colossus",
              "type": "MOB",
              "category": "BOSS",
              "name": "Twilight Colossus",
              "level": 45,
              "entityType": "IRON_GOLEM",
              "baseStats": [
                { "status": "MAX_HEALTH", "value": 18000 }
              ],
              "challenge": {
                "fieldWorldId": "twilight_colossus_field",
                "entryLocation": { "worldId": "boss_hub", "x": 0.5, "y": 64.0, "z": 4.5 },
                "entryRadius": 3.0,
                "playerSpawnLocation": { "x": 0.5, "y": 64.0, "z": -8.5 },
                "bossSpawnLocation": { "x": 0.5, "y": 64.0, "z": 8.5 },
                "partyMin": 1,
                "partyMax": 6,
                "timeLimitSeconds": 600,
                "deathLimit": 5,
                "reviveDelaySeconds": 5,
                "scaling": {
                  "enabled": true,
                  "healthPerExtraPlayer": 35.0,
                  "attackPerExtraPlayer": 10.0
                }
              }
            }
            """;

        var mob = JsonSerializer.Deserialize<MobResponse>(json, MasterDataJsonOptions());

        Assert.NotNull(mob);
        Assert.NotNull(mob!.Challenge);
        Assert.Equal("twilight_colossus_field", mob.Challenge!.FieldWorldId);
        Assert.Equal("boss_hub", mob.Challenge.EntryLocation.WorldId);
        Assert.Equal(6, mob.Challenge.PartyMax);
        Assert.Equal(5, mob.Challenge.DeathLimit);
        Assert.Equal(5, mob.Challenge.ReviveDelaySeconds);
        Assert.NotNull(mob.Challenge.Scaling);
        Assert.True(mob.Challenge.Scaling!.Enabled);
        Assert.Equal(35.0D, mob.Challenge.Scaling.HealthPerExtraPlayer);
    }

    [Fact]
    public void DeserializeLiteralJson_PopulatesVariant()
    {
        var json = """
            {
              "schemaVersion": 1,
              "id": "village_elder",
              "type": "MOB",
              "category": "NPC",
              "name": "Village Elder",
              "level": 1,
              "entityType": "VILLAGER",
              "variant": {
                "age": "ADULT",
                "villagerType": "PLAINS",
                "profession": "LIBRARIAN",
                "villagerLevel": 3,
                "scale": 4.0
              },
              "baseStats": []
            }
            """;

        var mob = JsonSerializer.Deserialize<MobResponse>(json, MasterDataJsonOptions());

        Assert.NotNull(mob);
        Assert.NotNull(mob!.Variant);
        Assert.Equal("ADULT", mob.Variant!.Age);
        Assert.Equal("PLAINS", mob.Variant.VillagerType);
        Assert.Equal("LIBRARIAN", mob.Variant.Profession);
        Assert.Equal(3, mob.Variant.VillagerLevel);
        Assert.Equal(4.0D, mob.Variant.Scale);
    }

    [Fact]
    public void DeserializeLiteralJson_PreservesMobLevelProfiles()
    {
        var mob = JsonSerializer.Deserialize<MobResponse>("""
            {
              "schemaVersion": 1,
              "id": "midgard_grassboar",
              "type": "MOB",
              "category": "ENEMY",
              "name": "grassboar",
              "level": 1,
              "entityType": "PIG",
              "baseStats": [],
              "levels": [
                { "level": 1, "baseStats": [{ "status": "ATTACK", "value": 18 }] },
                { "level": 2, "baseStats": [{ "status": "ATTACK", "value": 28 }] }
              ]
            }
            """, MasterDataJsonOptions());

        Assert.NotNull(mob);
        Assert.Equal(2, mob!.Levels.Count);
        Assert.Equal(2, mob.Levels[1].GetProperty("level").GetInt32());
        Assert.Equal(28, mob.Levels[1].GetProperty("baseStats")[0].GetProperty("value").GetInt32());
    }

    private static JsonSerializerOptions MasterDataJsonOptions()
    {
        var payloadType = typeof(MobRepository)
            .Assembly
            .GetType("AstralRecordApi.Repositories.MasterDataPayloadJson", throwOnError: true)!;

        return (JsonSerializerOptions)payloadType
            .GetField("Options", BindingFlags.Public | BindingFlags.Static)!
            .GetValue(null)!;
    }

    private static void AssertRareCharm(MobDropItemResponse item, string expectedItemId)
    {
        Assert.Equal($"item:{expectedItemId}", item.ItemId);
        Assert.Equal(0.1D, item.Rate);
        Assert.Equal("1", item.Amount);
        Assert.True(item.LuckAffected);
        Assert.False(item.Hidden);
    }

    private static string ResolveWorkspaceRoot([CallerFilePath] string currentFile = "")
    {
        var current = new FileInfo(currentFile).Directory;
        while (current is not null)
        {
            if (Directory.Exists(Path.Combine(current.FullName, "40_filebase"))
                && Directory.Exists(Path.Combine(current.FullName, "20_api")))
            {
                return current.FullName;
            }

            current = current.Parent;
        }

        throw new InvalidOperationException("workspace root could not be resolved from the test source path.");
    }
}
