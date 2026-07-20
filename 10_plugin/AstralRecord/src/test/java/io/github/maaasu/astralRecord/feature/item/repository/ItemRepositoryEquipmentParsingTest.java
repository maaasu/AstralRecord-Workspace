package io.github.maaasu.astralRecord.feature.item.repository;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemRepositoryEquipmentParsingTest {

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
                  "runes":null,
                  "enchantPools":null
                }
                """);

        assertEquals(0, instance.getDurabilityMax());
        assertEquals(0, instance.getDurabilityValue());
        assertEquals("", instance.getCreatedAt());
        assertEquals("", instance.getUpdatedAt());
        assertTrue(instance.getStatRolls().isEmpty());
        assertTrue(instance.getEnchants().isEmpty());
        assertTrue(instance.getRunes().isEmpty());
        assertTrue(instance.getEnchantPools().isEmpty());
    }

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
