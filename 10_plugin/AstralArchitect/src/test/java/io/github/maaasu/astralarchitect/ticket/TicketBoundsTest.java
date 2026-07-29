package io.github.maaasu.astralarchitect.ticket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketBoundsTest {

    @Test
    void calculatesInclusiveDimensionsAndVolume() {
        TicketBounds bounds = new TicketBounds(
                new BlockPosition(-2, 60, 10),
                new BlockPosition(2, 63, 12));

        assertEquals(5, bounds.width());
        assertEquals(4, bounds.height());
        assertEquals(3, bounds.length());
        assertEquals(60L, bounds.volume());
    }

    @Test
    void containsBothInclusiveEdges() {
        TicketBounds bounds = new TicketBounds(
                new BlockPosition(1, 2, 3),
                new BlockPosition(4, 5, 6));

        assertTrue(bounds.contains(new BlockPosition(1, 2, 3)));
        assertTrue(bounds.contains(new BlockPosition(4, 5, 6)));
        assertFalse(bounds.contains(new BlockPosition(5, 5, 6)));
    }
}
