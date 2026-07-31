package io.github.maaasu.astralRecord.feature.currency.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GoldCurrencyCalculatorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/16-currency/16_1-モデル定義.md
     * 章・見出し: # 16_1-モデル定義 > ## 4. ゴールド計算結果
     * 検証契約: 880 goldを8 gold_ingotと8 gold_coinの正規額面表現へ分解する。
     */
    @Test
    void decomposesChangeFromOneThousandAfterSpendingOneHundredTwenty() {
        Map<GoldDenomination, Long> change = GoldCurrencyCalculator.decompose(1_000L - 120L);

        assertEquals(8L, change.get(GoldDenomination.GOLD_INGOT));
        assertEquals(8L, change.get(GoldDenomination.GOLD_COIN));
        assertEquals(2, change.size());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/16-currency/16_0-概要.md
     * 章・見出し: # 16_0-概要 > ## 3. 組み込みゴールド
     * 検証契約: 各額面数量へ換算値を乗算して5 gold・2 coin・3 diamondを30025 goldと算出する。
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
     * 設計入力: 00_docs/10_Plugin設計書/feature/16-currency/16_0-概要.md
     * 章・見出し: # 16_0-概要 > ## 3. 組み込みゴールド
     * 検証契約: 120 gold支払い時に低額面から消費し、不足分だけ上位額面を崩して残高775を構成する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/16-currency/16_1-モデル定義.md
     * 章・見出し: # 16_1-モデル定義 > ## 4. ゴールド計算結果
     * 検証契約: 100 goldしかない状態で101 goldを要求した場合は支払結果を生成せずnullを返す。
     */
    @Test
    void returnsNullWithoutChangingRepresentationWhenTotalIsInsufficient() {
        assertNull(GoldCurrencyCalculator.spendSmallestFirst(
            denomination -> denomination == GoldDenomination.GOLD_INGOT ? 1L : 0L,
            101L
        ));
    }
}
