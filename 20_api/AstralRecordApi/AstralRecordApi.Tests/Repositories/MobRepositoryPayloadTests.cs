using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using System.Reflection;
using System.Text.Json;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class MobRepositoryPayloadTests
{
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
                "max": 10
              },
              "ai": {
                "idle": { "behavior": "WANDER" },
                "targeting": { "strategy": "NEAREST", "aggroRange": 12 },
                "combat": { "style": "MELEE" }
              }
            }
            """;

        var mob = JsonSerializer.Deserialize<MobResponse>(json, MasterDataJsonOptions());

        Assert.NotNull(mob);
        Assert.NotNull(mob!.Shield);
        Assert.True(mob.Shield!.Enabled);
        Assert.Equal(10.0D, mob.Shield.Max);
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
}
