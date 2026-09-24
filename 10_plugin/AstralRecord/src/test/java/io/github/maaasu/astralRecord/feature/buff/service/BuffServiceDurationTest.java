package io.github.maaasu.astralRecord.feature.buff.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuffServiceDurationTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/05-buff/3-メソッド仕様/05_3-サービス.md
     * 章・見出し: # 05_3-サービス > ## 1. BuffService メソッド仕様 > ### バフ付与
     * 検証契約: 30秒のバフへ100%増加を適用すると60秒になり、tick単位の端数も保持する。
     */
    @Test
    void doublesThirtySecondBuffAndRoundsToTicks() {
        assertEquals(1200L, BuffService.scaledDurationTicks(600, 100.0D));
        assertEquals(3L, BuffService.scaledDurationTicks(2, 25.0D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/05-buff/3-メソッド仕様/05_3-サービス.md
     * 章・見出し: # 05_3-サービス > ## 1. BuffService メソッド仕様 > ### バフ付与
     * 検証契約: 不正な増加率は基礎時間を短縮せず、極端な増加率も安全な上限内に収める。
     */
    @Test
    void ignoresInvalidRatesAndCapsExtremeDuration() {
        assertEquals(600L, BuffService.scaledDurationTicks(600, -50.0D));
        assertEquals(600L, BuffService.scaledDurationTicks(600, Double.NaN));
        assertEquals(Integer.MAX_VALUE, BuffService.scaledDurationTicks(Integer.MAX_VALUE, 100.0D));
    }
}
