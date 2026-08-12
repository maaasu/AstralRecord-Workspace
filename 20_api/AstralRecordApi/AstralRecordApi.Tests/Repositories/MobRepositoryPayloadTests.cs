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
    public void DeserializeLiteralJson_PopulatesShield()
    {
        var json = """
            {
              "schemaVersion": 1,
              "id": "goblin_warrior",
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
              "category": "NPC",
              "name": "Village Elder",
              "level": 1,
              "entityType": "VILLAGER",
              "variant": {
                "age": "ADULT",
                "villagerType": "PLAINS",
                "profession": "LIBRARIAN",
                "villagerLevel": 3
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
