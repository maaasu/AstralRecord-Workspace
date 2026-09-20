package io.github.maaasu.astralRecord.feature.combat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CombatTimingCalculatorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/07_1-モデル定義.md
     * 章・見出し: # 07_1-モデル定義 > ## 4. ステータス種別 > ### 4.7 回復・ユーティリティ系
     * 検証契約: CDをceil(base×(1-clamp(reduction,0,50)/100))で短縮し、50%を下限倍率にする。
     */
    @Test
    void cooldownReductionShortensCooldownLinearlyAndCapsAtFiftyPercent() {
        assertEquals(80L, CombatTimingCalculator.resolveCooldownTicks(100L, 20.0D));
        assertEquals(50L, CombatTimingCalculator.resolveCooldownTicks(100L, 100.0D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 6.4 アーケインフロー
     * 検証契約: ステータスと追加詠唱短縮を乗算しても合計短縮率は50%を超えない。
     */
    @Test
    void stackedCastTimeReductionCapsAtFiftyPercent() {
        assertEquals(0.72D,
            CombatTimingCalculator.resolveStackedTimeReductionMultiplier(20.0D, 10.0D), 0.0001D);
        assertEquals(0.5D,
            CombatTimingCalculator.resolveStackedTimeReductionMultiplier(50.0D, 50.0D), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/07_1-モデル定義.md
     * 章・見出し: # 07_1-モデル定義 > ## 4. ステータス種別 > ### 4.3 攻撃系
     * 検証契約: 攻撃間隔をceil(base×100/attackSpeed)で計算し正の基本間隔は最低1tickにする。
     */
    @Test
    void attackSpeedUsesOneHundredAsTheBaseAndKeepsAtLeastOneTick() {
        assertEquals(20L, CombatTimingCalculator.resolveAttackIntervalTicks(20L, 100.0D));
        assertEquals(10L, CombatTimingCalculator.resolveAttackIntervalTicks(20L, 200.0D));
        assertEquals(1L, CombatTimingCalculator.resolveAttackIntervalTicks(1L, 1_000.0D));
    }
}
