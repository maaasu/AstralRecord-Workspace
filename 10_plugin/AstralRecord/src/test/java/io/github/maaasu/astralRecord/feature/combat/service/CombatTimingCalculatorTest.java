package io.github.maaasu.astralRecord.feature.combat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CombatTimingCalculatorTest {

    @Test
    void cooldownReductionShortensCooldownLinearlyAndAllowsZero() {
        assertEquals(80L, CombatTimingCalculator.resolveCooldownTicks(100L, 20.0D));
        assertEquals(0L, CombatTimingCalculator.resolveCooldownTicks(100L, 100.0D));
    }

    @Test
    void attackSpeedUsesOneHundredAsTheBaseAndKeepsAtLeastOneTick() {
        assertEquals(20L, CombatTimingCalculator.resolveAttackIntervalTicks(20L, 100.0D));
        assertEquals(10L, CombatTimingCalculator.resolveAttackIntervalTicks(20L, 200.0D));
        assertEquals(1L, CombatTimingCalculator.resolveAttackIntervalTicks(1L, 1_000.0D));
    }
}
