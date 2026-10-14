using System.Reflection;
using System.Text.Json;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class MasterDataPayloadContractTests
{
    [Fact]
    public void EquipmentStatRandom_PreservesBaseAndSeparateRollDefinition()
    {
        var item = Deserialize<ItemResponse>(EquipmentFixture("12.5", "\"random\": {\"offsetMin\": -2.5, \"offsetMax\": 1.5, \"step\": 0.5, \"zeroChance\": 0.2}"));
        var stat = Assert.Single(item.Equipment!.Stats);
        Assert.Equal("12.5", stat.Value!.Min);
        Assert.Equal("12.5", stat.Value.Max);
        Assert.Equal(-2.5, stat.Random!.OffsetMin);
        Assert.Equal(1.5, stat.Random.OffsetMax);
        Assert.Equal(0.5, stat.Random.Step);
        Assert.Equal(0.2, stat.Random.ZeroChance);

        using var document = JsonDocument.Parse(JsonSerializer.Serialize(item, MasterDataJsonOptions()));
        var normalized = document.RootElement.GetProperty("equipment").GetProperty("stats")[0];
        Assert.Equal("12.5", normalized.GetProperty("value").GetProperty("min").GetString());
        Assert.Equal("12.5", normalized.GetProperty("value").GetProperty("max").GetString());
        Assert.Equal(0.5, normalized.GetProperty("random").GetProperty("step").GetDouble());
    }

    [Theory]
    [InlineData("{\"min\": 10, \"max\": 10}", "\"random\": {\"offsetMin\": 1, \"offsetMax\": 2, \"step\": 1}")]
    [InlineData("\"10~10\"", "\"random\": {\"offsetMin\": 1, \"offsetMax\": 2, \"step\": 1}")]
    [InlineData("10", "\"random\": {\"offsetMin\": 0, \"offsetMax\": 1, \"step\": 0}")]
    [InlineData("10", "\"random\": {\"offsetMin\": 0, \"offsetMax\": 1, \"step\": 0.3}")]
    [InlineData("10", "\"random\": {\"offsetMin\": 0, \"offsetMax\": 1, \"step\": 0.00001}")]
    [InlineData("10", "\"random\": {\"offsetMin\": 0, \"offsetMax\": 1000000, \"step\": 1}")]
    [InlineData("0", "\"random\": {\"offsetMin\": -1, \"offsetMax\": 1, \"step\": 1, \"zeroChance\": 0.1}")]
    [InlineData("10.00001", "\"random\": {\"offsetMin\": 1, \"offsetMax\": 2, \"step\": 1}")]
    [InlineData("10", "\"min\": 9, \"random\": {\"offsetMin\": 1, \"offsetMax\": 2, \"step\": 1}")]
    [InlineData("10", "\"random\": {\"offsetMin\": 1e-100, \"offsetMax\": 2, \"step\": 1}")]
    [InlineData("10000000000000000", "\"random\": {\"offsetMin\": -1, \"offsetMax\": 1, \"step\": 1}")]
    [InlineData("9007199254740993", "\"random\": {\"offsetMin\": -1, \"offsetMax\": -1, \"step\": 1}")]
    [InlineData("1e-1000", "\"random\": {\"offsetMin\": 0, \"offsetMax\": 1, \"step\": 1}")]
    [InlineData("10", "\"random\": {\"offsetMax\": 2, \"step\": 1}")]
    [InlineData("10", "\"random\": {\"offsetMin\": 2, \"offsetMax\": 1, \"step\": 1}")]
    [InlineData("10", "\"random\": {\"offsetMin\": 0, \"offsetMax\": 1, \"step\": 1, \"zeroChance\": -0.1}")]
    [InlineData("10", "\"random\": {\"offsetMin\": 0, \"offsetMax\": 1, \"step\": 1, \"zeroChance\": 1.1}")]
    [InlineData("79228162514264337593543950335", "\"random\": {\"offsetMin\": 1, \"offsetMax\": 1, \"step\": 1}")]
    public void EquipmentStatRandom_RejectsInvalidContract(string value, string random)
        => Assert.Throws<JsonException>(() => Deserialize<ItemResponse>(EquipmentFixture(value, random)));

    [Fact]
    public void EquipmentStatLegacyScalarAndRangeRemainReadable()
    {
        var scalar = Assert.Single(Deserialize<ItemResponse>(EquipmentFixture("10", null)).Equipment!.Stats);
        var range = Assert.Single(Deserialize<ItemResponse>(EquipmentFixture("{\"min\": \"2~4\", \"max\": \"5~7\"}", null)).Equipment!.Stats);
        Assert.Equal("10", scalar.Value!.Min);
        Assert.Null(scalar.Random);
        Assert.Equal("2~4", range.Value!.Min);
        Assert.Equal("5~7", range.Value.Max);
        Assert.Null(range.Random);
    }

    [Fact]
    public void EquipmentStatRandom_DefaultsZeroChanceToZero()
    {
        var stat = Assert.Single(Deserialize<ItemResponse>(EquipmentFixture("10", "\"random\": {\"offsetMin\": 1, \"offsetMax\": 2, \"step\": 1}")).Equipment!.Stats);
        Assert.Equal(0, stat.Random!.ZeroChance);
    }

    private static string EquipmentFixture(string value, string? random) => $$"""
        {
          "schemaVersion": 1,
          "id": "fixed_equipment_fixture",
          "category": "equipment",
          "name": "Fixture",
          "icon": "IRON_SWORD",
          "rarity": "COMMON",
          "equipment": {
            "slot": "WEAPON",
            "stats": [{"status": "MELEE_ATTACK", "type": "FLAT", "value": {{value}}{{(random is null ? "" : ", " + random)}}}]
          }
        }
        """;

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
              "level": 2,
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
        Assert.Equal(2, gathering.Level);
        Assert.Equal("GATHERING_SPAWNER", spawner.Type);
    }

    [Fact]
    public void World_PreservesRequiredItemReference()
    {
        var world = Deserialize<WorldResponse>("""
            {
              "schemaVersion": 1,
              "id": "eriva_supercontinent",
              "displayName": "エリヴァ超大陸",
              "worldType": "OVERWORLD",
              "baseWorldPath": "plugins/AstralRecord/worlds/overworld/eriva_supercontinent",
              "instanceRootPath": "plugins/AstralRecord/_world_instances/eriva_supercontinent",
              "spawnLocation": { "x": 0.5, "y": 64.0, "z": 0.5, "yaw": 0.0, "pitch": 0.0 },
              "description": "Eriva",
              "requiredItemId": { "ref": "item:eriva_waystone" }
            }
            """);

        Assert.Equal("item:eriva_waystone", world.RequiredItemId);
        using var document = JsonDocument.Parse(JsonSerializer.Serialize(world, MasterDataJsonOptions()));
        Assert.Equal(
            "item:eriva_waystone",
            document.RootElement.GetProperty("requiredItemId").GetString());
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
