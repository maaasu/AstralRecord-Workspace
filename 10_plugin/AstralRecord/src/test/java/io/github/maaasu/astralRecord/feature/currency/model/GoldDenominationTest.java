package io.github.maaasu.astralRecord.feature.currency.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GoldDenominationTest {

    @Test
    void denominationsIncreaseByOneDecimalPlace() {
        assertArrayEquals(
            new long[]{1L, 10L, 100L, 1_000L},
            java.util.Arrays.stream(GoldDenomination.values())
                .mapToLong(GoldDenomination::goldValue)
                .toArray()
        );
    }

    @Test
    void resolvesDenominationByItemId() {
        assertEquals(GoldDenomination.GOLD_INGOT, GoldDenomination.findByItemId("gold_ingot"));
        assertNull(GoldDenomination.findByItemId("astrald"));
    }
}
