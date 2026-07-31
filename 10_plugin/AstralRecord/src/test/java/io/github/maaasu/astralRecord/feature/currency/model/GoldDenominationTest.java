package io.github.maaasu.astralRecord.feature.currency.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GoldDenominationTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/16-currency/16_0-概要.md
     * 章・見出し: # 16_0-概要 > ## 3. 組み込みゴールド
     * 検証契約: 組み込み7額面を1から1000000まで10倍刻みの低額面順で定義する。
     */
    @Test
    void denominationsIncreaseByOneDecimalPlace() {
        assertArrayEquals(
            new long[]{1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L},
            java.util.Arrays.stream(GoldDenomination.values())
                .mapToLong(GoldDenomination::goldValue)
                .toArray()
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/16-currency/16_1-モデル定義.md
     * 章・見出し: # 16_1-モデル定義 > ## 1. ゴールド額面
     * 検証契約: 既知item ID gold_ingotを対応額面へ解決し、未知IDはnullとする。
     */
    @Test
    void resolvesDenominationByItemId() {
        assertEquals(GoldDenomination.GOLD_INGOT, GoldDenomination.findByItemId("gold_ingot"));
        assertNull(GoldDenomination.findByItemId("astrald"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/16-currency/16_1-モデル定義.md
     * 章・見出し: # 16_1-モデル定義 > ## 1. ゴールド額面
     * 検証契約: 隣接額面、直下交換比率10、最高額面YGGDRASIL_STAR_COREを返す。
     */
    @Test
    void exposesAdjacentDenominationsAndHighestTier() {
        assertEquals(GoldDenomination.GOLD_DIAMOND, GoldDenomination.GOLD_BLOCK.higher());
        assertEquals(GoldDenomination.GOLD_BLOCK, GoldDenomination.GOLD_DIAMOND.lower());
        assertEquals(10L, GoldDenomination.GOLD_DIAMOND.lowerExchangeRatio());
        assertEquals(GoldDenomination.YGGDRASIL_STAR_CORE, GoldDenomination.highest());
    }
}
