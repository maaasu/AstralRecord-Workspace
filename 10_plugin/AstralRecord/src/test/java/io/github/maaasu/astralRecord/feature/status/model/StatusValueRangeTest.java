package io.github.maaasu.astralRecord.feature.status.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusValueRangeTest {

    @Test
    void fixedValueReturnsSingleTotal() {
        StatusValue value = new StatusValue(8.0D, 4.0D);

        assertEquals(12.0D, value.getMinValue(), 0.0001D);
        assertEquals(12.0D, value.getMaxValue(), 0.0001D);
        assertEquals(12.0D, value.rollValue(), 0.0001D);
    }

    @Test
    void rangeValueRollsWithinTotalBounds() {
        StatusValue value = new StatusValue(8.0D, 8.0D, 2.0D, 12.0D);

        assertEquals(10.0D, value.getMinValue(), 0.0001D);
        assertEquals(20.0D, value.getMaxValue(), 0.0001D);
        for (int i = 0; i < 1_000; i++) {
            double rolled = value.rollValue();
            assertTrue(rolled >= 10.0D && rolled <= 20.0D);
        }
    }

    @Test
    void statusFormattingCollapsesEqualRange() {
        assertEquals("26", StatusType.ATTACK.formatRange(26.0D, 26.0D));
        assertEquals("20 ～ 30", StatusType.ATTACK.formatRange(20.0D, 30.0D));
    }
}
