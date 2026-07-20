package io.github.maaasu.astralRecord.feature.currency.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
