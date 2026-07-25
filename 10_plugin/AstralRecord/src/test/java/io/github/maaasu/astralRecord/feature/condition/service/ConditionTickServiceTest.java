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
import static org.mockito.Mockito.when;

class ConditionTickServiceTest {

    @Test
    void burningUsesMaximumHealthRateAndFixedSnapshot() {
        ConditionService conditionService = mock(ConditionService.class);
        DamageService damageService = mock(DamageService.class);
        AstEntity target = mock(AstEntity.class);
        AstEntity source = mock(AstEntity.class);
        when(target.maxHealth()).thenReturn(100.0D);
        ActiveCondition condition = condition(
            ConditionType.BURNING, target, source, 2.0D, 0.01D, 20, 0L, Long.MAX_VALUE
        );

        new ConditionTickService(conditionService, damageService).tickCondition(condition, 100L);

        verify(damageService).applyConditionDamage(source, target, 3.0D, ConditionType.BURNING);
        verify(conditionService).pulse(condition);
        assertEquals(1_100L, condition.nextTickAtMs());
    }

    @Test
    void poisonUsesCurrentHealthRate() {
        ConditionService conditionService = mock(ConditionService.class);
        DamageService damageService = mock(DamageService.class);
        AstEntity target = mock(AstEntity.class);
        when(target.currentHealth()).thenReturn(50.0D);
        ActiveCondition condition = condition(
            ConditionType.POISON, target, null, 0.0D, 0.03D, 20, 0L, Long.MAX_VALUE
        );

        new ConditionTickService(conditionService, damageService).tickCondition(condition, 100L);

        verify(damageService).applyConditionDamage(null, target, 1.5D, ConditionType.POISON);
    }

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
