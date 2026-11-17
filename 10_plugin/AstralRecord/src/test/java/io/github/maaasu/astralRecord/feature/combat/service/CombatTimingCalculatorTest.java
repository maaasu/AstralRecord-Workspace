package io.github.maaasu.astralRecord.feature.combat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CombatTimingCalculatorTest {

    /**
     * CDをceil(base×(1-clamp(reduction,0,50)/100))で短縮し、50%を下限倍率にする。
     */
    @Test
    void cooldownReductionShortensCooldownLinearlyAndCapsAtFiftyPercent() {
        assertEquals(80L, CombatTimingCalculator.resolveCooldownTicks(100L, 20.0D));
        assertEquals(50L, CombatTimingCalculator.resolveCooldownTicks(100L, 100.0D));
    }

    /**
     * ステータスと追加詠唱短縮を乗算しても合計短縮率は50%を超えない。
     */
    @Test
    void stackedCastTimeReductionCapsAtFiftyPercent() {
        assertEquals(0.72D,
            CombatTimingCalculator.resolveStackedTimeReductionMultiplier(20.0D, 10.0D), 0.0001D);
        assertEquals(0.5D,
            CombatTimingCalculator.resolveStackedTimeReductionMultiplier(50.0D, 50.0D), 0.0001D);
    }

    /**
     * 攻撃間隔をceil(base×100/attackSpeed)で計算し正の基本間隔は最低1tickにする。
     */
    @Test
    void attackSpeedUsesOneHundredAsTheBaseAndKeepsAtLeastOneTick() {
        assertEquals(20L, CombatTimingCalculator.resolveAttackIntervalTicks(20L, 100.0D));
        assertEquals(10L, CombatTimingCalculator.resolveAttackIntervalTicks(20L, 200.0D));
        assertEquals(1L, CombatTimingCalculator.resolveAttackIntervalTicks(1L, 1_000.0D));
    }
}
