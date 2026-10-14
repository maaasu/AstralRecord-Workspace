package io.github.maaasu.astralRecord.feature.item.repository;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ItemRepositoryEquipmentParsingTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_1-モデル定義.md
     * 章・見出し: # 04_1-モデル定義 > ## 4. カテゴリ固有定義 > ### 4.3 `ItemEquipment`
     * 検証契約: nullable durability/collection項目を安全な空/defaultへ変換する。
     */
    @Test
    void nullableDurabilityAndCollectionsUseSafeDefaults() throws Exception {
        EquipmentInstance instance = parseEquipmentInstance("""
                {
                  "equipmentInstanceId":"instance-1",
                  "accountId":"account-1",
                  "itemId":"lucky_amulet",
                  "enhanceLevel":0,
                  "runeMaxSlots":0,
                  "transcendenceRank":0,
                  "durabilityMax":null,
                  "durabilityValue":null,
                  "createdAt":null,
                  "updatedAt":null,
                  "statRolls":null,
                  "enchants":null,
                  "runes":null
                }
                """);

        assertEquals(0, instance.getDurabilityMax());
        assertEquals(0, instance.getDurabilityValue());
        assertEquals("", instance.getCreatedAt());
        assertEquals("", instance.getUpdatedAt());
        assertTrue(instance.getStatRolls().isEmpty());
        assertTrue(instance.getEnchants().isEmpty());
        assertTrue(instance.getRunes().isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_1-モデル定義.md
     * 章・見出し: # 04_1-モデル定義 > ## 4. カテゴリ固有定義 > ### 4.3 `ItemEquipment`
     * 検証契約: 装備level/class条件とtranscendence強化条件をDTOから保持する。
     */
    @Test
    void equipmentRequirementsAndTranscendenceEnhanceRequirementAreParsed() throws Exception {
        ItemModel item = parseItem("""
            {
              "schemaVersion":1,
              "id":"class_blade",
              "category":"equipment",
              "name":"class blade",
              "icon":"IRON_SWORD",
              "rarity":"COMMON",
              "maxStack":1,
              "equipment":{
                "slot":"WEAPON",
                "requiredLevel":5,
                "requiredClasses":[{"classId":"swordsman","level":3}],
                "enhance":{"maxLevel":5,"levels":[]},
                "transcendence":[{
                  "name":"覚醒",
                  "rank":1,
                  "requiredEnhanceLevel":5,
                  "requiredMaterials":[{"itemId":"awakening_stone","amount":2}],
                  "requiredCurrency":100
                }]
              }
            }
            """);

        assertEquals(5, item.getEquipment().getRequiredLevel());
        assertEquals("swordsman", item.getEquipment().getRequiredClasses().getFirst().getClassId());
        assertEquals(3, item.getEquipment().getRequiredClasses().getFirst().getLevel());
        assertEquals(5, item.getEquipment().getTranscendence().getFirst().getRequiredEnhanceLevel());
        assertEquals(2, item.getEquipment().getTranscendence().getFirst().getRequiredMaterials().getFirst().getAmount());
        assertEquals(100, item.getEquipment().getTranscendence().getFirst().getRequiredCurrency());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_1-モデル定義.md
     * 章・見出し: # 04_1-モデル定義 > ## 4. カテゴリ固有定義 > ### 4.3 `ItemEquipment`
     * 検証契約: min/max の個別乱数範囲を計算用の下限/上限とは別に表示用モデルへ保持する。
     */
    @Test
    void equipmentStatRandomBoundsArePreservedForDisplay() throws Exception {
        ItemModel item = parseItem("""
            {
              "schemaVersion":1,
              "id":"random_sword",
              "category":"equipment",
              "name":"random sword",
              "icon":"IRON_SWORD",
              "rarity":"COMMON",
              "maxStack":1,
              "equipment":{
                "slot":"WEAPON",
                "stats":[
                  {"status":"MELEE_ATTACK","type":"FLAT","value":{"min":"2~3","max":"4~5"}},
                  {"status":"STRENGTH","type":"FLAT","value":{"min":"3","max":"4~5"}}
                ]
              }
            }
            """);

        assertEquals(2.0D, item.getEquipment().getStats().get(0).getMin());
        assertEquals(5.0D, item.getEquipment().getStats().get(0).getMax());
        assertEquals("2~3", item.getEquipment().getStats().get(0).getRawMin());
        assertEquals("4~5", item.getEquipment().getStats().get(0).getRawMax());
        assertEquals("3", item.getEquipment().getStats().get(1).getRawMin());
        assertEquals("4~5", item.getEquipment().getStats().get(1).getRawMax());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_1-モデル定義.md
     * 章・見出し: # 04_1-モデル定義 > ## 4. カテゴリ固有定義 > ### 4.8 `ItemOrb`
     * 検証契約: Seeder 用の enchant 参照prefixは Plugin内部のマスタIDから除く。
     */
    @Test
    void orbEnchantReferencePrefixIsNormalized() throws Exception {
        ItemModel item = parseItem("""
            {
              "schemaVersion":1,
              "id":"enchant_fill_orb",
              "category":"orb",
              "name":"enchant orb",
              "icon":"PRISMARINE_CRYSTALS",
              "rarity":"RARE",
              "maxStack":64,
              "orb":{
                "effect":{
                  "type":"ENCHANT",
                  "enchantMasterId":"enchant:enchant001",
                  "enchantOperation":"FILL_ONE_EMPTY"
                }
              }
            }
            """);

        assertEquals("enchant001", item.getOrb().getEffect().getEnchantMasterId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_1-モデル定義.md
     * 章・見出し: # 04_1-モデル定義 > ## 4. カテゴリ固有定義 > ### 4.5 `ItemRune`
     * 検証契約: ルーンの対象slotと任意の対象装備tagをAPI payloadから別々に保持する。
     */
    @Test
    void runeTargetTagsAreParsedSeparatelyFromTargetSlots() throws Exception {
        ItemModel item = parseItem("""
            {
              "schemaVersion":1,
              "id":"sword_rune",
              "category":"rune",
              "name":"sword rune",
              "icon":"REDSTONE",
              "rarity":"COMMON",
              "rune":{
                "targetSlots":["WEAPON"],
                "targetTags":["SWORD"],
                "stats":[]
              }
            }
            """);

        assertEquals(List.of("WEAPON"), item.getRune().getTargetSlots());
        assertEquals(List.of("SWORD"), item.getRune().getTargetTags());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_1-モデル定義.md
     * 章・見出し: # 04_1-モデル定義 > ## 4. カテゴリ固有定義 > ### 4.4 `ItemConsumable`
     * 検証契約: 消耗品の使用中サウンドと使用完了後サウンドを別々のマスタ項目として保持する。
     */
    @Test
    void consumableUsingAndCompletionSoundsAreParsedSeparately() throws Exception {
        ItemModel item = parseItem("""
            {
              "schemaVersion":1,
              "id":"energy_baked",
              "category":"consumable",
              "name":"energy baked",
              "icon":"BREAD",
              "rarity":"COMMON",
              "consumable":{
                "onUse":{
                  "usingSound":"entity.generic.eat",
                  "sound":"block.note_block.chime"
                },
                "effects":[]
              }
            }
            """);

        assertEquals("entity.generic.eat", item.getConsumable().getOnUse().getUsingSound());
        assertEquals("block.note_block.chime", item.getConsumable().getOnUse().getSound());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_1-モデル定義.md
     * 章・見出し: # 04_1-モデル定義 > ## 5. 所有インスタンス > ### 5.1 `EquipmentInstance`
     * 検証契約: APIの固定flagは読込とJSON保存で保全し、欠落・nullの旧行は未固定へ正規化する。
     */
    @Test
    void enchantLockIsPreservedAcrossRepositoryAndJsonSnapshotPaths() throws Exception {
        for (String flag : java.util.List.of("", ", \"isLocked\": null", ", \"isLocked\": true", ", \"isLocked\": false")) {
            String json = """
                {"equipmentInstanceId":"i","accountId":"a","itemId":"item",
                 "enchants":[{"enchantId":"e","equipmentInstanceId":"i","slotIndex":2,
                  "enchantMasterId":"master","effectId":"effect","status":"ATTACK","type":"FLAT","value":5%s}]}
                """.formatted(flag);
            EquipmentInstance parsed = parseEquipmentInstance(json);
            boolean expected = flag.endsWith("true");
            assertEquals(expected, parsed.getEnchants().getFirst().isLocked());
            var gson = new com.google.gson.Gson();
            var serialized = gson.toJsonTree(parsed).getAsJsonObject();
            assertEquals(expected, serialized.getAsJsonArray("enchants").get(0).getAsJsonObject().get("isLocked").getAsBoolean());
            // MarketRepositoryの装備読込も同じGsonモデル変換を利用する。
            EquipmentInstance marketCopy = gson.fromJson(serialized, EquipmentInstance.class);
            assertEquals(parsed, marketCopy);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_1-モデル定義.md
     * 章・見出し: # 04_1-モデル定義 > ## 4. カテゴリ固有定義 > ### 4.3 `ItemEquipment` > #### 生成時一回のステータス乱数
     * 検証契約: 明示randomだけを保持し、未指定scalarと旧rangeの値および生値を変更しない。
     */
    @Test
    void explicitRandomAndLegacyValuesRemainSeparate() throws Exception {
        ItemModel item = parseItem("""
            {"schemaVersion":1,"id":"fixture","category":"equipment","name":"fixture",
             "icon":"IRON_SWORD","rarity":"COMMON","equipment":{"slot":"WEAPON","stats":[
              {"status":"MELEE_ATTACK","type":"FLAT","value":{"min":"100","max":"100"},
               "random":{"offsetMin":-8,"offsetMax":8,"step":1}},
              {"status":"DEFENSE","type":"FLAT","value":12,"random":null},
              {"status":"MAGIC_ATTACK","type":"FLAT","value":{"min":"2~3","max":"4~5"}}
             ]}}
            """);
        var stats = item.getEquipment().getStats();
        assertNotNull(stats.get(0).getRandom());
        assertEquals(0, stats.get(0).getRandom().zeroChance());
        assertEquals(100, stats.get(0).getMin());
        assertNull(stats.get(1).getRandom());
        assertEquals(12, stats.get(1).getMin());
        assertEquals(12, stats.get(1).getMax());
        assertNull(stats.get(2).getRandom());
        assertEquals("2~3", stats.get(2).getRawMin());
        assertEquals("4~5", stats.get(2).getRawMax());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_1-モデル定義.md
     * 章・見出し: # 04_1-モデル定義 > ## 4. カテゴリ固有定義 > ### 4.3 `ItemEquipment` > #### 生成時一回のステータス乱数
     * 検証契約: 新規個体の明示randomを同値min/maxへ確定し、保存再読込で個体を再抽選しない。
     */
    @Test
    void generatedScalarRollSurvivesSnapshotReload() throws Exception {
        ItemModel item = parseItem("""
            {"schemaVersion":1,"id":"fixture","category":"equipment","name":"fixture",
             "icon":"IRON_SWORD","rarity":"COMMON","equipment":{"slot":"WEAPON","stats":[
              {"status":"MELEE_ATTACK","type":"FLAT","value":100,
               "random":{"offsetMin":-8,"offsetMax":8,"step":1}},
              {"status":"DEFENSE","type":"FLAT","value":12}
             ]}}
            """);
        var service = new io.github.maaasu.astralRecord.feature.item.service.ItemService();
        var instance = service.createLocalEquipmentInstance(item, java.util.UUID.randomUUID());
        assertNotNull(instance);
        var roll = instance.getStatRolls().getFirst();
        assertEquals(roll.getMin(), roll.getMax());
        assertTrue(Double.parseDouble(roll.getMin()) >= 92 && Double.parseDouble(roll.getMax()) <= 108);
        assertEquals("12", instance.getStatRolls().get(1).getMin());
        assertEquals("12", instance.getStatRolls().get(1).getMax());
        String snapshot = new com.google.gson.Gson().toJson(instance);
        assertEquals(instance, parseEquipmentInstance(snapshot));
        assertEquals(instance, parseEquipmentInstance(snapshot));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_1-モデル定義.md
     * 章・見出し: # 04_1-モデル定義 > ## 4. カテゴリ固有定義 > ### 4.3 `ItemEquipment` > #### 生成時一回のステータス乱数
     * 検証契約: 過去の個体に保存された異なるmin/maxもマスター乱数とは独立にそのまま復元する。
     */
    @Test
    void legacyInstanceBoundsAreNeverRerolledOnLoad() throws Exception {
        String snapshot = """
            {"equipmentInstanceId":"old","accountId":"account","itemId":"fixture",
             "statRolls":[{"statRollId":"saved","status":"MELEE_ATTACK","min":"3.25","max":"8.75","sortOrder":2}]}
            """;
        for (int attempt = 0; attempt < 3; attempt++) {
            var roll = parseEquipmentInstance(snapshot).getStatRolls().getFirst();
            assertEquals("saved", roll.getStatRollId());
            assertEquals("3.25", roll.getMin());
            assertEquals("8.75", roll.getMax());
            assertEquals(2, roll.getSortOrder());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_1-モデル定義.md
     * 章・見出し: # 04_1-モデル定義 > ## 4. カテゴリ固有定義 > ### 4.3 `ItemEquipment` > #### 生成時一回のステータス乱数
     * 検証契約: 同値の範囲形式・欠落した刻み・double表現不能な基準値や加算幅を明示randomで拒否する。
     */
    @Test
    void explicitRandomRejectsLegacyRangeAndMissingStep() {
        for (String stat : List.of(
            "\"value\":\"100~100\",\"random\":{\"offsetMin\":1,\"offsetMax\":2,\"step\":1}",
            "\"value\":{\"min\":\"100~100\",\"max\":\"100~100\"},\"random\":{\"offsetMin\":1,\"offsetMax\":2,\"step\":1}",
            "\"value\":9007199254740993,\"random\":{\"offsetMin\":-1,\"offsetMax\":-1,\"step\":1}",
            "\"value\":0,\"random\":{\"offsetMin\":9007199254740993,\"offsetMax\":9007199254740993,\"step\":1}",
            "\"value\":100,\"min\":100,\"random\":{\"offsetMin\":1,\"offsetMax\":2,\"step\":1}",
            "\"value\":100,\"random\":{\"offsetMin\":1,\"offsetMax\":2}")) {
            String payload = """
                {"schemaVersion":1,"id":"fixture","category":"equipment","name":"fixture",
                 "icon":"IRON_SWORD","rarity":"COMMON","equipment":{"slot":"WEAPON","stats":[
                   {"status":"MELEE_ATTACK","type":"FLAT",%s}]}}
                """.formatted(stat);
            var failure = org.junit.jupiter.api.Assertions.assertThrows(
                java.lang.reflect.InvocationTargetException.class, () -> parseItem(payload));
            org.junit.jupiter.api.Assertions.assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
    }

    private EquipmentInstance parseEquipmentInstance(String json) throws Exception {
        ItemRepository repository = new ItemRepository();
        Method parser = ItemRepository.class.getDeclaredMethod("parseEquipmentInstance", String.class);
        parser.setAccessible(true);
        return (EquipmentInstance) parser.invoke(repository, json);
    }

    private ItemModel parseItem(String json) throws Exception {
        ItemRepository repository = new ItemRepository();
        Method parser = ItemRepository.class.getDeclaredMethod("parseItem", String.class);
        parser.setAccessible(true);
        return (ItemModel) parser.invoke(repository, json);
    }
}
