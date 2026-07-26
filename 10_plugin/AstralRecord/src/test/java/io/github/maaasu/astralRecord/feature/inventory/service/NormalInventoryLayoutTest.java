package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals(268_435_456, NormalInventoryLayout.totalRows(Integer.MAX_VALUE));
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
    void bagCapacityCannotFallBackToPersistedConfiguration() {
        assertThrows(IllegalArgumentException.class,
            () -> NormalInventoryLayout.effectiveCapacity(InventoryType.BAG, 24));
        assertThrows(IllegalArgumentException.class,
            () -> NormalInventoryLayout.effectiveCapacity(InventoryType.BAG, null));
        assertThrows(IllegalArgumentException.class,
            () -> NormalInventoryLayout.effectiveCapacity(InventoryType.BAG, 40));
        assertEquals(24, NormalInventoryLayout.effectiveCapacity(InventoryType.EQUIP_SLOT, 24));
    }

    @Test
    void displayCapacityKeepsOverflowEntriesReachable() {
        InventoryEntryModel overflow = entryAtSlot(40);

        assertEquals(40, NormalInventoryLayout.displayCapacity(List.of(overflow), 24));
        assertEquals(2, NormalInventoryLayout.maxScrollRow(
            NormalInventoryLayout.displayCapacity(List.of(overflow), 24)));
        assertEquals(1L, NormalInventoryLayout.overflowCount(List.of(overflow), 24));
        assertEquals(0L, NormalInventoryLayout.overflowCount(List.of(overflow), 40));
    }

    private static InventoryEntryModel entryAtSlot(int slot) {
        UUID actor = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            UUID.randomUUID(),
            UUID.randomUUID(),
            slot,
            "MATERIAL",
            "test_item",
            null,
            null,
            1L,
            null,
            now,
            now,
            actor,
            actor,
            false
        );
    }
}
