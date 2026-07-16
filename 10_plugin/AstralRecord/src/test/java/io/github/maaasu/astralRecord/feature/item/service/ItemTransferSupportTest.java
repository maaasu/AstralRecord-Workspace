package io.github.maaasu.astralRecord.feature.item.service;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemTransferSupportTest {

    @Test
    void resolvesConsistentTransferAmountsForSingleStackAndAllStacks() {
        assertEquals(1, ItemTransferSupport.resolveTransferAmount(ClickType.LEFT, 130, 64));
        assertEquals(65, ItemTransferSupport.resolveTransferAmount(ClickType.RIGHT, 130, 64));
        assertEquals(64, ItemTransferSupport.resolveTransferAmount(ClickType.SHIFT_LEFT, 130, 64));
        assertEquals(16, ItemTransferSupport.resolveTransferAmount(ClickType.SHIFT_LEFT, 130, 16));
        assertEquals(130, ItemTransferSupport.resolveTransferAmount(ClickType.SHIFT_RIGHT, 130, 64));
        assertEquals(0, ItemTransferSupport.resolveTransferAmount(ClickType.MIDDLE, 130, 64));
    }

    @Test
    void recognizesOnlyShiftRightAsAllStacksTransfer() {
        assertTrue(ItemTransferSupport.isAllStacksTransfer(ClickType.SHIFT_RIGHT));
        assertFalse(ItemTransferSupport.isAllStacksTransfer(ClickType.SHIFT_LEFT));
        assertFalse(ItemTransferSupport.isAllStacksTransfer(ClickType.RIGHT));
    }
}
