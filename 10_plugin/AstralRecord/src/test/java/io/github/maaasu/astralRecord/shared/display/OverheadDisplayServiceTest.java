package io.github.maaasu.astralRecord.shared.display;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OverheadDisplayServiceTest {

    @Test
    void mobPassengerOffsetUsesFixedHeadClearance() {
        Vector offset = OverheadDisplayService.mobPassengerOverheadOffset();

        assertEquals(0.0D, offset.getX());
        assertEquals(0.15D, offset.getY());
        assertEquals(0.0D, offset.getZ());
    }
}
