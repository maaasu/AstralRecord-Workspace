package io.github.maaasu.astralRecord.feature.currency.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GoldCurrencyCalculatorTest {

    /**
     * 各額面数量へ換算値を乗算して5 gold・2 coin・3 diamondを30025 goldと算出する。
     */
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

    /**
     * 100 goldしかない状態で101 goldを要求した場合は支払結果を生成せずnullを返す。
     */
    @Test
    void returnsNullWithoutChangingRepresentationWhenTotalIsInsufficient() {
        assertNull(GoldCurrencyCalculator.spendSmallestFirst(
            denomination -> denomination == GoldDenomination.GOLD_INGOT ? 1L : 0L,
            101L
        ));
    }
}
