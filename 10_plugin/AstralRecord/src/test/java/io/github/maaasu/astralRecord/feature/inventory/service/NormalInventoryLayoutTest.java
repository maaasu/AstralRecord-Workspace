package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalInventoryLayoutTest {

    @Test
    void mapsTwentyFourVisibleItemsAroundRightControlColumn() {
        assertEquals(24, NormalInventoryLayout.VISIBLE_CAPACITY);
        assertEquals(32, NormalInventoryLayout.DEFAULT_CAPACITY);
        assertEquals(9, NormalInventoryLayout.toGuiSlotIndex(1, 0));
        assertEquals(16, NormalInventoryLayout.toGuiSlotIndex(8, 0));
        assertEquals(18, NormalInventoryLayout.toGuiSlotIndex(9, 0));
        assertEquals(34, NormalInventoryLayout.toGuiSlotIndex(24, 0));
        assertFalse(NormalInventoryLayout.isManagedGuiSlot(17));
        assertFalse(NormalInventoryLayout.isManagedGuiSlot(26));
        assertFalse(NormalInventoryLayout.isManagedGuiSlot(35));
        assertEquals(1, NormalInventoryLayout.maxScrollRow(NormalInventoryLayout.DEFAULT_CAPACITY));
    }

    @Test
    void scrollsOneEightItemRowAtATime() {
        assertEquals(9, NormalInventoryLayout.toGuiSlotIndex(9, 1));
        assertEquals(34, NormalInventoryLayout.toGuiSlotIndex(32, 1));
        assertEquals(9, NormalInventoryLayout.toGuiSlotIndex(17, 2));
        assertEquals(34, NormalInventoryLayout.toGuiSlotIndex(40, 2));
        assertEquals(17, NormalInventoryLayout.toDbSlotIndex(9, 2));
        assertEquals(40, NormalInventoryLayout.toDbSlotIndex(34, 2));
        assertEquals(2, NormalInventoryLayout.maxScrollRow(40));
    }

    @Test
    void freeSlotSearchHonorsConfiguredCapacity() {
        Set<Integer> used = new HashSet<>();
        for (int slot = 1; slot <= 39; slot++) {
            used.add(slot);
        }
        assertEquals(40, NormalInventoryLayout.findNextFreeSlot(used, 40));
        used.add(40);
        assertNull(NormalInventoryLayout.findNextFreeSlot(used, 40));
        assertTrue(NormalInventoryLayout.isManagedSlot(40, 40));
        assertFalse(NormalInventoryLayout.isManagedSlot(41, 40));
    }

    @Test
    void legacyBagCapacityIsRaisedToCurrentDefault() {
        assertEquals(32, NormalInventoryLayout.effectiveCapacity(InventoryType.BAG, 24));
        assertEquals(32, NormalInventoryLayout.effectiveCapacity(InventoryType.BAG, null));
        assertEquals(40, NormalInventoryLayout.effectiveCapacity(InventoryType.BAG, 40));
        assertEquals(24, NormalInventoryLayout.effectiveCapacity(InventoryType.EQUIP_SLOT, 24));
    }
}
