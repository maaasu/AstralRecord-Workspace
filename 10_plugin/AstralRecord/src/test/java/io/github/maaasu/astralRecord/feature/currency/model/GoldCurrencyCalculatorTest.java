package io.github.maaasu.astralRecord.feature.currency.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GoldCurrencyCalculatorTest {

    @Test
    void decomposesChangeFromOneThousandAfterSpendingOneHundredTwenty() {
        Map<GoldDenomination, Long> change = GoldCurrencyCalculator.decompose(1_000L - 120L);

        assertEquals(8L, change.get(GoldDenomination.GOLD_INGOT));
        assertEquals(8L, change.get(GoldDenomination.GOLD_COIN));
        assertEquals(2, change.size());
    }

    @Test
    void totalsAllDenominationsByGoldValue() {
        long total = GoldCurrencyCalculator.totalValue(denomination -> switch (denomination) {
            case GOLD -> 5L;
            case GOLD_COIN -> 2L;
            case GOLD_DIAMOND -> 3L;
            default -> 0L;
        });

        assertEquals(30_025L, total);
    }

    @Test
    void spendsLowerDenominationsBeforeBreakingHigherOnes() {
        Map<GoldDenomination, Long> remaining = GoldCurrencyCalculator.spendSmallestFirst(
            denomination -> switch (denomination) {
                case GOLD -> 5L;
                case GOLD_COIN -> 9L;
                case GOLD_INGOT -> 8L;
                default -> 0L;
            },
            120L
        );

        assertEquals(5L, remaining.get(GoldDenomination.GOLD));
        assertEquals(7L, remaining.get(GoldDenomination.GOLD_COIN));
        assertEquals(7L, remaining.get(GoldDenomination.GOLD_INGOT));
        assertEquals(775L, GoldCurrencyCalculator.totalValue(
            denomination -> remaining.getOrDefault(denomination, 0L)
        ));
    }

    @Test
    void returnsNullWithoutChangingRepresentationWhenTotalIsInsufficient() {
        assertNull(GoldCurrencyCalculator.spendSmallestFirst(
            denomination -> denomination == GoldDenomination.GOLD_INGOT ? 1L : 0L,
            101L
        ));
    }
}
