using System.Text.Json.Nodes;

namespace AstralRecordApi.Tests.TestSupport;

/// <summary>
/// Plugin/API の repository テストで使用する最小 master payload。
/// 本番の 40_filebase は読み込まず、テスト対象の契約に必要な項目だけを固定する。
/// </summary>
internal static class MasterDataTestFixtures
{
    public const string AdventurerSmash = """
        {
          "schemaVersion": 1,
          "id": "adventurer_smash",
          "type": "SKILL",
          "implementationId": "adventurer_smash",
          "name": "&bスマッシュ",
          "icon": "IRON_AXE",
          "maxLevel": 5,
          "sigilSlotsByLevel": [{ "level": 1, "slots": 1 }, { "level": 3, "slots": 2 }, { "level": 5, "slots": 3 }],
          "allowedSigilIds": ["cooldown_sigil", "cooldown_sigil_ii"],
          "gem": { "rarity": "COMMON", "tradeable": false, "sellable": false },
          "tags": ["active", "melee", "adventurer"]
        }
        """;

    public const string AdventurerMeditation = """
        {
          "schemaVersion": 1,
          "id": "adventurer_meditation",
          "type": "SKILL",
          "implementationId": "adventurer_meditation",
          "name": "&dメディテーション",
          "icon": "CAMPFIRE",
          "maxLevel": 1,
          "passive": { "bindRequired": true },
          "params": { "regenMultiplier": 3 },
          "gem": { "rarity": "COMMON", "tradeable": false, "sellable": false },
          "tags": ["passive", "field"]
        }
        """;

    public const string HunterCrashArrow = """
        {
          "schemaVersion": 1,
          "id": "hunter_crash_arrow",
          "type": "SKILL",
          "implementationId": "hunter_crash_arrow",
          "name": "&bクラッシュアロー",
          "icon": "TARGET",
          "maxLevel": 5,
          "levels": [
            { "level": 2, "paramDeltas": { "shieldBreakMultiplier": 0.5 } },
            { "level": 3, "paramDeltas": { "shieldBreakMultiplier": 0.5 } },
            { "level": 4, "paramDeltas": { "shieldBreakMultiplier": 0.5 } },
            { "level": 5, "paramDeltas": { "shieldBreakMultiplier": 0.5 } }
          ],
          "params": { "shieldBreakMultiplier": 3.0 },
          "gem": { "rarity": "COMMON", "tradeable": false, "sellable": false },
          "tags": ["active", "ranged"]
        }
        """;

    public const string CooldownSigil = """
        {
          "schemaVersion": 1,
          "id": "cooldown_sigil",
          "category": "sigil",
          "name": "&b短縮のシジル",
          "icon": "AMETHYST_SHARD",
          "rarity": "UNCOMMON",
          "maxStack": 64,
          "sigil": {
            "equipGroupId": "cooldown_reduction",
            "modifiers": [{ "status": "COOLDOWN_REDUCTION", "value": 5 }]
          }
        }
        """;

    public const string CooldownSigilIi = """
        {
          "schemaVersion": 1,
          "id": "cooldown_sigil_ii",
          "category": "sigil",
          "name": "&9短縮のシジルⅡ",
          "icon": "ECHO_SHARD",
          "rarity": "RARE",
          "maxStack": 64,
          "sigil": {
            "equipGroupId": "cooldown_reduction",
            "modifiers": [{ "status": "COOLDOWN_REDUCTION", "value": 10 }]
          }
        }
        """;

    public const string HomingFireballSigil = """
        {
          "schemaVersion": 1,
          "id": "homing_fireball_sigil",
          "category": "sigil",
          "name": "&d追尾火焔のシジル",
          "icon": "FIREWORK_STAR",
          "rarity": "EPIC",
          "maxStack": 64,
          "sigil": { "equipGroupId": "fireball_trajectory", "modifiers": [] }
        }
        """;

    public const string MageClass = """
        {
          "schemaVersion": 1,
          "id": "mage",
          "type": "CLASS",
          "name": "&bメイジ",
          "order": 1.3,
          "shortName": "&bMAG",
          "role": "DEALER",
          "baseStats": []
        }
        """;

    public const string Enchant001 = """
        {
          "schemaVersion": 1,
          "id": "enchant001",
          "targets": [
            { "equipmentType": "WEAPON", "entries": [{ "effectId": "weapon_attack_scalar_130", "status": "ATTACK", "type": "SCALAR", "value": "1.30", "weight": 30 }] },
            { "equipmentType": "ARMOR", "entries": [{ "effectId": "armor_defense_scalar_130", "status": "DEFENSE", "type": "SCALAR", "value": "1.30", "weight": 30 }] },
            { "equipmentType": "ACCESSORY", "entries": [{ "effectId": "accessory_luck_scalar_120", "status": "LUCK", "type": "SCALAR", "value": "1.20", "weight": 20 }] }
          ]
        }
        """;

    public const string CompensationMail = """
        {
          "schemaVersion": 1,
          "id": "skilltree_structure_reset_compensation",
          "icon": "WRITABLE_BOOK",
          "title": "スキルツリー選択状態のリセットについて",
          "body": "スキルツリー構造の更新に伴い、選択状態をリセットしました。",
          "publishFrom": "2026-01-01T00:00:00",
          "publishTo": null,
          "receiveOnRead": true,
          "rewards": [{ "itemId": "astrald", "category": "currency", "amount": 50 }]
        }
        """;

    public const string Ainurindale = """
        {
          "schemaVersion": 1,
          "id": "ainurindale",
          "type": "MOB",
          "category": "ENEMY",
          "name": "&5アイヌリンダレ",
          "level": 8,
          "entityType": "EVOKER",
          "ai": {
            "combat": {
              "style": "MAGIC",
              "skills": [{
                "id": "mob_ainurindale_fang_wave",
                "activationRange": 14,
                "cooldownTicks": 100,
                "castTimeTicks": 20,
                "params": { "damageRatio": 0.45, "hitRadius": 1.25, "laneSpacing": 1.8, "waveIntervalTicks": 8 }
              }]
            }
          },
          "drops": {
            "items": [
              { "itemId": "item:merian_charm", "rate": 0.1, "amount": "1", "luckAffected": true, "hidden": false },
              { "itemId": "item:balrog_charm", "rate": 0.1, "amount": "1", "luckAffected": true, "hidden": false },
              { "itemId": "item:sauron_charm", "rate": 0.1, "amount": "1", "luckAffected": true, "hidden": false }
            ],
            "lootTable": "loot_table:normal_enemy_common_table_tier_1"
          }
        }
        """;

    public const string ShieldGuard = """
        {
          "schemaVersion": 1,
          "id": "midgard_shield_guard",
          "type": "MOB",
          "category": "ENEMY",
          "name": "&7シールドガード",
          "level": 3,
          "entityType": "ZOMBIE",
          "shield": { "enabled": true, "max": 4, "rechargeTimeSeconds": 15 }
        }
        """;

    public const string SkeletonArcher = """
        {
          "schemaVersion": 1,
          "id": "midgard_skeleton_archer",
          "type": "MOB",
          "category": "ENEMY",
          "name": "&fスケルトン・アーチャー",
          "level": 2,
          "entityType": "SKELETON",
          "ai": {
            "combat": {
              "style": "RANGED",
              "skills": [{
                "id": "mob_skeleton_bow_shot",
                "activationRange": 16,
                "cooldownTicks": 36,
                "castTimeTicks": 12,
                "params": { "damageRatio": 0.85 }
              }]
            }
          }
        }
        """;

    public static string Get(string masterId) => masterId switch
    {
        "adventurer_smash" => AdventurerSmash,
        "cooldown_sigil" => CooldownSigil,
        "cooldown_sigil_ii" => CooldownSigilIi,
        "homing_fireball_sigil" => HomingFireballSigil,
        _ => throw new ArgumentOutOfRangeException(nameof(masterId), masterId, "No inline test fixture exists."),
    };

    public static IReadOnlyList<(string Payload, string Category)> EnhancementItems =>
    [
        (CreateEquipmentItem("merian_charm", "ACCESSORY", "CHARM", 8, [("MAGIC_ATTACK", 10), ("INTELLIGENCE", 2)]), "equipment"),
        (CreateEquipmentItem("balrog_charm", "ACCESSORY", "CHARM", 8, [("RANGED_ATTACK", 10), ("DEXTERITY", 2)]), "equipment"),
        (CreateEquipmentItem("sauron_charm", "ACCESSORY", "CHARM", 8, [("MELEE_ATTACK", 10), ("STRENGTH", 2)]), "equipment"),
        (CreateConsumableItem("energy_baked"), "consumable"),
        (CreateOrbItem("tyr_orb", CreateEnhanceEffect("WEAPON", "SUBWEAPON")), "orb"),
        (CreateOrbItem("aegis_orb", CreateEnhanceEffect("HEAD", "CHEST", "LEGS", "FEET")), "orb"),
        (CreateOrbItem("freya_orb", CreateEnhanceEffect("ACCESSORY")), "orb"),
        (CreateOrbItem("enchant_overwrite_orb", CreateEnchantEffect("OVERWRITE_RANDOM")), "orb"),
        (CreateOrbItem("enchant_fill_orb", CreateEnchantEffect("FILL_ONE_EMPTY")), "orb"),
        (CreateOrbItem("enchant_fill_all_orb", CreateEnchantEffect("FILL_ALL_EMPTY")), "orb"),
        (CreateOrbItem("high_tyr_orb", CreateRankedEnhanceEffect(5, "AT_MOST", "WEAPON")), "orb"),
        (CreateOrbItem("transcendence_orb", CreateRankedEffect(1, "EXACT")), "orb"),
        (CreateOrbItem("high_transcendence_orb", CreateRankedEffect(5, "AT_MOST")), "orb"),
        (CreateOrbItem("sindri_orb", new JsonObject { ["type"] = "REPAIR", ["repairAmount"] = 75, ["repairFull"] = false }), "orb"),
        (CreateOrbItem("full_repair_orb", new JsonObject { ["type"] = "REPAIR", ["repairFull"] = true }), "orb"),
        (CreateEquipmentItem("nox_sword", "WEAPON", null, 0, [("MELEE_ATTACK", 17)], CreateEnhance("MELEE_ATTACK")), "equipment"),
        (CreateEquipmentItem("nox_bow", "WEAPON", null, 0, [("RANGED_ATTACK", 17)], CreateEnhance("RANGED_ATTACK")), "equipment"),
        (CreateEquipmentItem("nox_staff", "WEAPON", null, 0, [("MAGIC_ATTACK", 17)], CreateEnhance("MAGIC_ATTACK")), "equipment"),
        (CreateEquipmentItem("nox_armor_chest", "CHEST", null, 0, [("DEFENSE", 17)], CreateEnhance("DEFENSE")), "equipment"),
        (CreateEquipmentItem("nox_armor_boots", "FEET", null, 0, [("DEFENSE", 17)], CreateEnhance("DEFENSE")), "equipment"),
        (CreateEquipmentItem("nox_armor_helmet", "HEAD", null, 0, [("DEFENSE", 17)], CreateEnhance("DEFENSE")), "equipment"),
        (CreateEquipmentItem("nox_armor_legs", "LEGS", null, 0, [("DEFENSE", 17)], CreateEnhance("DEFENSE")), "equipment"),
    ];

    private static string CreateEquipmentItem(
        string id,
        string slot,
        string? tag,
        int requiredLevel,
        IReadOnlyList<(string Status, int Value)> stats,
        JsonObject? enhance = null)
    {
        var statNodes = new JsonArray();
        foreach (var stat in stats)
        {
            statNodes.Add(new JsonObject
            {
                ["status"] = stat.Status,
                ["type"] = "FLAT",
                ["value"] = stat.Value,
            });
        }

        var equipment = new JsonObject
        {
            ["slot"] = slot,
            ["requiredLevel"] = requiredLevel,
            ["stats"] = statNodes,
        };
        if (tag is not null)
            equipment["tag"] = tag;
        if (enhance is not null)
            equipment["enhance"] = enhance;

        return new JsonObject
        {
            ["schemaVersion"] = 1,
            ["id"] = id,
            ["name"] = id,
            ["icon"] = "IRON_NUGGET",
            ["rarity"] = "EPIC",
            ["equipment"] = equipment,
        }.ToJsonString();
    }

    private static string CreateConsumableItem(string id)
        => new JsonObject
        {
            ["schemaVersion"] = 1,
            ["id"] = id,
            ["name"] = id,
            ["icon"] = "BREAD",
            ["rarity"] = "COMMON",
            ["consumable"] = new JsonObject
            {
                ["onUse"] = new JsonObject
                {
                    ["usingSound"] = "entity.generic.eat",
                    ["useTimeTicks"] = 16,
                    ["cooldownTicks"] = 16,
                },
            },
        }.ToJsonString();

    private static string CreateOrbItem(string id, JsonObject effect)
        => new JsonObject
        {
            ["schemaVersion"] = 1,
            ["id"] = id,
            ["name"] = id,
            ["icon"] = "NETHER_STAR",
            ["rarity"] = "RARE",
            ["orb"] = new JsonObject { ["effect"] = effect },
        }.ToJsonString();

    private static JsonObject CreateEnhanceEffect(params string[] targetSlots)
    {
        var slots = new JsonArray();
        foreach (var targetSlot in targetSlots)
            slots.Add(targetSlot);
        return new JsonObject { ["type"] = "ENHANCE", ["targetSlots"] = slots };
    }

    private static JsonObject CreateRankedEnhanceEffect(int rank, string rankMode, params string[] targetSlots)
    {
        var effect = CreateEnhanceEffect(targetSlots);
        effect["rank"] = rank;
        effect["rankMode"] = rankMode;
        return effect;
    }

    private static JsonObject CreateRankedEffect(int rank, string rankMode)
        => new() { ["type"] = "TRANSCENDENCE", ["rank"] = rank, ["rankMode"] = rankMode };

    private static JsonObject CreateEnchantEffect(string operation)
        => new()
        {
            ["type"] = "ENCHANT",
            ["enchantMasterId"] = "enchant:enchant001",
            ["enchantOperation"] = operation,
        };

    private static JsonObject CreateEnhance(string status)
    {
        var levels = new JsonArray();
        for (var level = 1; level <= 10; level++)
        {
            levels.Add(new JsonObject
            {
                ["level"] = level,
                ["statIncrease"] = new JsonArray
                {
                    new JsonObject { ["status"] = status, ["type"] = "FLAT", ["value"] = 1 },
                },
                ["successRate"] = 1.0,
                ["failAction"] = "NONE",
            });
        }

        return new JsonObject { ["maxLevel"] = 10, ["levels"] = levels };
    }
}
