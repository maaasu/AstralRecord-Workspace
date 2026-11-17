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
    void visibleSlotsRoundTripWithoutOverlappingInventoryControls() {
        Set<Integer> controls = Set.of(
            NormalInventoryLayout.SCROLL_UP_GUI_SLOT,
            NormalInventoryLayout.INFO_GUI_SLOT,
            NormalInventoryLayout.SCROLL_DOWN_GUI_SLOT
        );

        for (int scrollRow : List.of(0, 1, 2)) {
            int firstSlot = NormalInventoryLayout.DB_SLOT_START + scrollRow * NormalInventoryLayout.CONTENT_COLUMNS;
            int endSlot = firstSlot + NormalInventoryLayout.VISIBLE_CAPACITY;
            Set<Integer> mappedSlots = new HashSet<>();
            for (int dbSlot = firstSlot; dbSlot < endSlot; dbSlot++) {
                int guiSlot = NormalInventoryLayout.toGuiSlotIndex(dbSlot, scrollRow);
                assertTrue(NormalInventoryLayout.isManagedGuiSlot(guiSlot));
                assertFalse(controls.contains(guiSlot));
                assertTrue(mappedSlots.add(guiSlot));
                assertEquals(dbSlot, NormalInventoryLayout.toDbSlotIndex(guiSlot, scrollRow));
            }
            for (int control : controls) {
                assertEquals(-1, NormalInventoryLayout.toDbSlotIndex(control, scrollRow));
            }
            assertEquals(-1, NormalInventoryLayout.toGuiSlotIndex(firstSlot - 1, scrollRow));
            assertEquals(-1, NormalInventoryLayout.toGuiSlotIndex(endSlot, scrollRow));
        }
    }

    @Test
    void roundsUpRowCountWithoutOverflowingAtMaximumCapacity() {
        int rows = NormalInventoryLayout.totalRows(Integer.MAX_VALUE);
        int columns = NormalInventoryLayout.CONTENT_COLUMNS;

        assertTrue((rows - 1L) * columns < Integer.MAX_VALUE);
        assertTrue((long) rows * columns >= Integer.MAX_VALUE);
    }

    /**
     * 空slot探索をruntime実効容量内に限定する。
     */
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

    /**
     * BAG容量をpersisted slotCapacityへfallbackしない。
     */
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

    /**
     * 表示行数を実効容量と最大entry indexの大きい方で求め容量外entryへscroll到達可能にする。
     */
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
