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
