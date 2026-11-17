package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.DamageBreakdown;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

class DamageResultTest {

    /**
     * シールドに吸収された結果にも元のダメージ計算内訳を保持する。
     */
    @Test
    void shieldResultKeepsCalculationBreakdown() {
        DamageBreakdown breakdown = new DamageBreakdown(90.0D, 42.0D, 42.0D, List.of());
        DamageResult calculated = new DamageResult(48.0D, false, 76.0D, 82.0D, 6.0D, breakdown);
        DamageResult shield = DamageResult.shield(3.0D, true, calculated);

        assertSame(breakdown, shield.breakdown());
    }

}
