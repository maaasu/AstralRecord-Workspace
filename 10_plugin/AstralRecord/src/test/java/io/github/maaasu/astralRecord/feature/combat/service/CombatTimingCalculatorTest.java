package io.github.maaasu.astralRecord.feature.combat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CombatTimingCalculatorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/07_1-モデル定義.md
     * 章・見出し: # 07_1-モデル定義 > ## 4. ステータス種別 > ### 4.7 回復・ユーティリティ系
     * 検証契約: CDをceil(base×max(0,1-reduction/100))で短縮し100%以上は0tickにする。
     */
    @Test
    void cooldownReductionShortensCooldownLinearlyAndAllowsZero() {
        assertEquals(80L, CombatTimingCalculator.resolveCooldownTicks(100L, 20.0D));
        assertEquals(0L, CombatTimingCalculator.resolveCooldownTicks(100L, 100.0D));
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
