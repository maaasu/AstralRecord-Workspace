package io.github.maaasu.astralRecord.feature.item.repository;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
