package io.github.maaasu.astralRecord.feature.currency.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GoldDenominationTest {

    @Test
    void denominationsIncreaseByOneDecimalPlace() {
        assertArrayEquals(
            new long[]{1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L},
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

    @Test
    void exposesAdjacentDenominationsAndHighestTier() {
        assertEquals(GoldDenomination.GOLD_DIAMOND, GoldDenomination.GOLD_BLOCK.higher());
        assertEquals(GoldDenomination.GOLD_BLOCK, GoldDenomination.GOLD_DIAMOND.lower());
        assertEquals(10L, GoldDenomination.GOLD_DIAMOND.lowerExchangeRatio());
        assertEquals(GoldDenomination.YGGDRASIL_STAR_CORE, GoldDenomination.highest());
    }
}
