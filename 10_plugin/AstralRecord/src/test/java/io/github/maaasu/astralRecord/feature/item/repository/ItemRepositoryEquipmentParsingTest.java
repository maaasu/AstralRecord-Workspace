package io.github.maaasu.astralRecord.feature.item.repository;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
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

    private EquipmentInstance parseEquipmentInstance(String json) throws Exception {
        ItemRepository repository = new ItemRepository();
        Method parser = ItemRepository.class.getDeclaredMethod("parseEquipmentInstance", String.class);
        parser.setAccessible(true);
        return (EquipmentInstance) parser.invoke(repository, json);
    }
}
