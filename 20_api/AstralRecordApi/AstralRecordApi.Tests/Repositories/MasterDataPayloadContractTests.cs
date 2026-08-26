using System.Reflection;
using System.Text.Json;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class MasterDataPayloadContractTests
{
    [Fact]
    public void ItemConsumable_PreservesUseTiming_ThroughApiModelRoundTrip()
    {
        var item = Deserialize<ItemResponse>("""
            {
              "schemaVersion": 1,
              "id": "energy_baked",
              "category": "consumable",
              "name": "energy baked",
              "icon": "BREAD",
              "rarity": "COMMON",
              "consumable": {
                "onUse": {
                  "usingSound": "entity.generic.eat",
                  "sound": "block.note_block.chime",
                  "effect": "ELECTRIC_SPARK",
                  "amount": 1,
                  "useTimeTicks": 16,
                  "cooldownTicks": 16
                },
                "effects": []
              }
            }
            """);

        Assert.Equal(16, item.Consumable!.OnUse!.UseTimeTicks);
        Assert.Equal(16, item.Consumable.OnUse.CooldownTicks);
        Assert.Equal("entity.generic.eat", item.Consumable.OnUse.UsingSound);
        Assert.Equal("block.note_block.chime", item.Consumable.OnUse.Sound);

        using var document = JsonDocument.Parse(JsonSerializer.Serialize(item, MasterDataJsonOptions()));
        var onUse = document.RootElement
            .GetProperty("consumable")
            .GetProperty("onUse");
        Assert.Equal(16, onUse.GetProperty("useTimeTicks").GetInt64());
        Assert.Equal(16, onUse.GetProperty("cooldownTicks").GetInt64());
        Assert.Equal("entity.generic.eat", onUse.GetProperty("usingSound").GetString());
        Assert.Equal("block.note_block.chime", onUse.GetProperty("sound").GetString());
    }

    [Fact]
    public void ItemBundle_PreservesInlineSoundAndParticleObjects()
    {
        var item = Deserialize<ItemResponse>("""
            {
              "schemaVersion": 1,
              "id": "example_bundle",
              "category": "bundle",
              "name": "bundle",
              "icon": "CHEST",
              "rarity": "COMMON",
              "bundle": {
                "openTimeTicks": 10,
                "onUse": {
                  "sound": {
                    "sound": "block.chest.open",
                    "volume": 0.6,
                    "pitch": 1.28
                  },
                  "particle": {
                    "particle": "TOTEM_OF_UNDYING",
                    "count": 24,
                    "originOffsetY": 1.0,
                    "offsetX": 0.4,
                    "offsetY": 0.5,
                    "offsetZ": 0.4
                  }
                }
              }
            }
            """);

        Assert.Equal(10, item.Bundle!.OpenTimeTicks);
        Assert.Equal("block.chest.open", item.Bundle.OnUse!.Sound!.Sound);
        Assert.Equal(0.6, item.Bundle.OnUse.Sound.Volume);
        Assert.Equal(1.28, item.Bundle.OnUse.Sound.Pitch);
        Assert.Equal("TOTEM_OF_UNDYING", item.Bundle.OnUse.Particle!.Particle);
        Assert.Equal(24, item.Bundle.OnUse.Particle.Count);
        Assert.Equal(1.0, item.Bundle.OnUse.Particle.OriginOffsetY);
        Assert.Equal(0.4, item.Bundle.OnUse.Particle.OffsetX);
    }

    [Fact]
    public void ItemBundle_DefaultsOpenTimeTicksToTwenty()
    {
        var item = Deserialize<ItemResponse>("""
            {
              "schemaVersion": 1,
              "id": "default_bundle",
              "category": "bundle",
              "name": "bundle",
              "icon": "CHEST",
              "rarity": "COMMON",
              "bundle": {}
            }
            """);

        Assert.Equal(20, item.Bundle!.OpenTimeTicks);

        using var document = JsonDocument.Parse(JsonSerializer.Serialize(item, MasterDataJsonOptions()));
        Assert.Equal(
            20,
            document.RootElement.GetProperty("bundle").GetProperty("openTimeTicks").GetInt64());
    }

    [Fact]
    public void Mob_PreservesTypeAndOptionalDamageImmunity()
    {
        var mob = Deserialize<MobResponse>("""
            {
              "schemaVersion": 1,
              "id": "food_merchant",
              "type": "MOB",
              "category": "NPC",
              "name": "food merchant",
              "level": 1,
              "entityType": "VILLAGER",
              "damageImmune": true,
              "baseStats": []
            }
            """);

        Assert.Equal("MOB", mob.Type);
        Assert.True(mob.DamageImmune);

        var legacyMob = Deserialize<MobResponse>("""
            {
              "schemaVersion": 1,
              "id": "legacy_npc",
              "type": "MOB",
              "category": "NPC",
              "name": "legacy npc",
              "level": 1,
              "entityType": "VILLAGER",
              "baseStats": []
            }
            """);

        Assert.Null(legacyMob.DamageImmune);
        using var legacyJson = JsonDocument.Parse(JsonSerializer.Serialize(legacyMob, MasterDataJsonOptions()));
        Assert.False(legacyJson.RootElement.TryGetProperty("damageImmune", out _));
    }

    [Fact]
    public void GatheringAndSpawner_PreserveSchemaType()
    {
        var gathering = Deserialize<GatheringResponse>("""
            {
              "schemaVersion": 1,
              "id": "windleaf_patch",
              "type": "GATHERING",
              "category": "HARVESTING",
              "name": "windleaf",
              "maxHealth": 18,
              "displayBlock": "FERN",
              "displayScale": { "x": 1, "y": 1, "z": 1 },
              "requiredToolTags": []
            }
            """);
        var spawner = Deserialize<GatheringSpawnerResponse>("""
            {
              "schemaVersion": 1,
              "id": "windwait_herb_spawner",
              "type": "GATHERING_SPAWNER",
              "radiusMeters": 16,
              "spawnGatherings": [],
              "spawnTimes": [],
              "itemMaterial": "SPAWNER",
              "spawnLimit": {}
            }
            """);

        Assert.Equal("GATHERING", gathering.Type);
        Assert.Equal("GATHERING_SPAWNER", spawner.Type);
    }

    [Fact]
    public void Rune_PreservesTargetTags_ThroughApiModelRoundTrip()
    {
        var item = Deserialize<ItemResponse>("""
            {
              "schemaVersion": 1,
              "id": "sword_rune",
              "category": "rune",
              "name": "sword rune",
              "icon": "REDSTONE",
              "rarity": "COMMON",
              "rune": {
                "targetSlots": ["WEAPON"],
                "targetTags": ["SWORD"],
                "stats": []
              }
            }
            """);

        Assert.Equal(["WEAPON"], item.Rune!.TargetSlots);
        Assert.Equal(["SWORD"], item.Rune.TargetTags);

        using var document = JsonDocument.Parse(JsonSerializer.Serialize(item, MasterDataJsonOptions()));
        var rune = document.RootElement.GetProperty("rune");
        Assert.Equal("SWORD", rune.GetProperty("targetTags")[0].GetString());
    }

    private static T Deserialize<T>(string json)
        => JsonSerializer.Deserialize<T>(json, MasterDataJsonOptions())
            ?? throw new InvalidOperationException($"Failed to deserialize {typeof(T).Name}.");

    private static JsonSerializerOptions MasterDataJsonOptions()
    {
        var payloadType = typeof(ItemRepository)
            .Assembly
            .GetType("AstralRecordApi.Repositories.MasterDataPayloadJson", throwOnError: true)!;

        return (JsonSerializerOptions)payloadType
            .GetField("Options", BindingFlags.Public | BindingFlags.Static)!
            .GetValue(null)!;
    }
}
