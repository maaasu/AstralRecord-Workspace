package io.github.maaasu.astralRecord.feature.condition.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.display.ConditionDisplayService;
import io.github.maaasu.astralRecord.feature.condition.model.ActiveCondition;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyReason;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyRequest;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionRejectReason;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.mob.model.MobBaseStat;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ConditionServiceTest {

    @Test
    void applyChanceUsesIncreaseAndResistanceAsPercentages() {
        assertEquals(45.0D, ConditionService.calculateApplyChance(50.0D, 20.0D, 25.0D), 0.0001D);
        assertEquals(100.0D, ConditionService.calculateApplyChance(80.0D, 100.0D, 0.0D), 0.0001D);
        assertEquals(0.0D, ConditionService.calculateApplyChance(100.0D, 0.0D, 100.0D), 0.0001D);
    }

    @Test
    void keepsStrongerEffectAndOnlyExtendsExpiry() {
        ConditionService service = service();
        AstEntity target = AstEntity.mob(mob(MobCategory.ENEMY, List.of()));

        ActiveCondition first = service.applyCondition(request(
            target, null, ConditionType.BURNING, 100L, 2.0D, 10.0D
        )).condition();
        ActiveCondition afterWeaker = service.applyCondition(request(
            target, null, ConditionType.BURNING, 200L, 1.0D, 5.0D
        )).condition();

        assertEquals(2.0D, afterWeaker.strength(), 0.0001D);
        assertEquals(10.0D, afterWeaker.snapshotPower(), 0.0001D);
        assertTrue(afterWeaker.expiresAtMs() > first.startedAtMs() + 100L * 50L);

        long extendedExpiry = afterWeaker.expiresAtMs();
        ActiveCondition afterStrongerShorter = service.applyCondition(request(
            target, null, ConditionType.BURNING, 50L, 3.0D, 15.0D
        )).condition();

        assertEquals(3.0D, afterStrongerShorter.strength(), 0.0001D);
        assertEquals(15.0D, afterStrongerShorter.snapshotPower(), 0.0001D);
        assertEquals(extendedExpiry, afterStrongerShorter.expiresAtMs());
    }

    @Test
    void frozenDominatesChilledWithoutRemovingIt() {
        ConditionService service = service();
        AstEntity target = AstEntity.mob(mob(MobCategory.ENEMY, List.of()));

        service.applyCondition(request(target, null, ConditionType.CHILLED, 100L, 1.0D, null));
        assertEquals(0.5D, service.movementSpeedMultiplier(target), 0.0001D);

        service.applyCondition(request(target, null, ConditionType.FROZEN, 40L, 1.0D, null));
        assertFalse(service.canMove(target));
        assertTrue(service.getActiveConditions(target).stream()
            .anyMatch(condition -> condition.type() == ConditionType.CHILLED));

        service.removeCondition(target, ConditionType.FROZEN);
        assertTrue(service.canMove(target));
        assertEquals(0.5D, service.movementSpeedMultiplier(target), 0.0001D);
    }

    @Test
    void conditionDamageUsesIndependentIncreaseResistanceAndPenetration() {
        ConditionService service = service();
        AstEntity source = AstEntity.mob(mob(MobCategory.ENEMY, List.of(
            new MobBaseStat(StatusType.BURNING_DAMAGE_INCREASE.name(), 20.0D),
            new MobBaseStat(StatusType.BURNING_DAMAGE_PENETRATION.name(), 10.0D)
        )));
        AstEntity target = AstEntity.mob(mob(MobCategory.ENEMY, List.of(
            new MobBaseStat(StatusType.BURNING_DAMAGE_RESISTANCE.name(), 50.0D)
        )));

        assertEquals(0.72D, service.conditionDamageMultiplier(source, target, ConditionType.BURNING), 0.0001D);
    }

    @Test
    void negativeDotResistanceIncreasesConditionDamage() {
        ConditionService service = service();
        AstEntity target = AstEntity.mob(mob(MobCategory.ENEMY, List.of(
            new MobBaseStat(StatusType.BURNING_DAMAGE_RESISTANCE.name(), -25.0D)
        )));

        assertEquals(1.25D, service.conditionDamageMultiplier(null, target, ConditionType.BURNING), 0.0001D);
    }

    @Test
    void fullApplyResistanceAlwaysRejectsAndHealingInhibitionBlocksRecovery() {
        ConditionService service = service();
        AstEntity resistant = AstEntity.mob(mob(MobCategory.ENEMY, List.of(
            new MobBaseStat(StatusType.FROZEN_RESISTANCE.name(), 100.0D)
        )));
        var rejected = service.applyCondition(request(
            resistant, null, ConditionType.FROZEN, 40L, 1.0D, null
        ));
        assertFalse(rejected.success());
        assertEquals(ConditionRejectReason.CHANCE_FAILED, rejected.rejectReason());

        AstEntity target = AstEntity.mob(mob(MobCategory.ENEMY, List.of()));
        service.applyCondition(request(target, null, ConditionType.HEALING_INHIBITION, 100L, 1.0D, null));
        assertTrue(service.isHealingBlocked(target));
    }

    @Test
    void weaknessHalvesAllOutgoingDamageIncludingConditionSourceDamage() {
        ConditionService service = service();
        AstEntity source = AstEntity.mob(mob(MobCategory.ENEMY, List.of()));

        service.applyCondition(request(source, null, ConditionType.WEAKNESS, 100L, 1.0D, null));

        assertEquals(0.5D, service.damageDealtMultiplier(source), 0.0001D);
    }

    @Test
    void cleanupSweepRemovesConditionsExpiredAtProvidedTime() {
        ConditionService service = service();
        AstEntity target = AstEntity.mob(mob(MobCategory.ENEMY, List.of()));
        ActiveCondition condition = service.applyCondition(request(
            target, null, ConditionType.BURNING, 100L, 1.0D, null
        )).condition();

        assertEquals(1, service.purgeExpiredConditions(condition.expiresAtMs()));
        assertTrue(service.snapshotAllActiveConditions().isEmpty());

        var reapplied = service.applyCondition(request(
            target, null, ConditionType.BURNING, 100L, 1.0D, null
        ));
        assertTrue(reapplied.success());
        assertFalse(reapplied.updated());
    }

    private ConditionService service() {
        return new ConditionService(mock(ConditionDisplayService.class), null);
    }

    private ConditionApplyRequest request(
            AstEntity target,
            AstEntity source,
            ConditionType type,
            long durationTicks,
            double strength,
            Double basePower
    ) {
        return new ConditionApplyRequest(
            target,
            source,
            type,
            durationTicks,
            100.0D,
            strength,
            basePower,
            null,
            null,
            null,
            ConditionApplyReason.SKILL
        );
    }

    private MobInstance mob(MobCategory category, List<MobBaseStat> stats) {
        MobTemplate template = new MobTemplate(
            1,
            "condition_test_" + UUID.randomUUID(),
            category,
            "Condition Test",
            null,
            1,
            EntityType.IRON_GOLEM,
            false,
            null,
            List.of(),
            List.of(),
            null,
            MobEquipmentConfig.EMPTY,
            stats,
            MobShieldConfig.EMPTY,
            MobIdleConfig.defaults(),
            false,
            MobInteractionsConfig.EMPTY,
            null,
            null,
            null
        );
        return new MobInstance(UUID.randomUUID(), template, new Location(null, 0.0D, 0.0D, 0.0D));
    }
}
