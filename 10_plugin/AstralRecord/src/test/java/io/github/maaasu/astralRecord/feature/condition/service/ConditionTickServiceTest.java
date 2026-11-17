package io.github.maaasu.astralRecord.feature.condition.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.model.ActiveCondition;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConditionTickServiceTest {

    /**
     * 炎上と毒は対象HPに依存せず、付与時の値でダメージを与え、次の実行時刻を進める。
     */
    @Test
    void damageOverTimeUsesFixedSnapshotWithoutTargetHealthRate() {
        assertFixedSnapshotDamage(ConditionType.BURNING, mock(AstEntity.class), 2.0D, 0.01D);
        assertFixedSnapshotDamage(ConditionType.POISON, null, 16.0D, 0.03D);
    }

    private void assertFixedSnapshotDamage(ConditionType type, AstEntity source, double power, double healthRate) {
        ConditionService conditionService = mock(ConditionService.class);
        DamageService damageService = mock(DamageService.class);
        AstEntity target = mock(AstEntity.class);
        ActiveCondition condition = condition(
            type, target, source, power, healthRate, 20, 0L, Long.MAX_VALUE
        );

        new ConditionTickService(conditionService, damageService).tickCondition(condition, 100L);

        verify(damageService).applyConditionDamage(source, target, power, type);
        verify(conditionService).pulse(condition);
        assertEquals(1_100L, condition.nextTickAtMs());
    }

    /**
     * 感電制御時に6tickの移動禁止を設定し、次回制御を16〜32tick後へ置く。
     */
    @Test
    void shockedStartsSixTickMovementBlockAtRandomInterval() {
        ConditionService conditionService = mock(ConditionService.class);
        DamageService damageService = mock(DamageService.class);
        AstEntity target = mock(AstEntity.class);
        ActiveCondition condition = condition(
            ConditionType.SHOCKED, target, null, 0.0D, 0.0D, 0, Long.MAX_VALUE, 0L
        );

        new ConditionTickService(conditionService, damageService).tickCondition(condition, 100L);

        assertEquals(400L, condition.controlBlockedUntilMs());
        assertTrue(condition.nextControlAtMs() >= 900L);
        assertTrue(condition.nextControlAtMs() <= 1_700L);
    }

    private ActiveCondition condition(
            ConditionType type,
            AstEntity target,
            AstEntity source,
            double snapshotPower,
            double healthRate,
            int tickIntervalTicks,
            long nextTickAtMs,
            long nextControlAtMs
    ) {
        return new ActiveCondition(
            UUID.randomUUID(),
            type,
            target,
            source,
            0L,
            10_000L,
            nextTickAtMs,
            nextControlAtMs,
            1.0D,
            snapshotPower,
            healthRate,
            tickIntervalTicks
        );
    }
}
