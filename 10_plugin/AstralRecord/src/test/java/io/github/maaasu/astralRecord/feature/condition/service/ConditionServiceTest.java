package io.github.maaasu.astralRecord.feature.condition.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.display.ConditionDisplayService;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyReason;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyRequest;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.mob.model.MobBaseStat;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobVanillaEffectProtectionService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ConditionServiceTest {

    @Test
    void burningStacksPowerAndKeepsMaxSnapshotPower() {
        ConditionService service = conditionService();
        AstEntity source = AstEntity.mob(mobWithStats(
                MobCategory.ENEMY,
                stat(StatusType.ATTACK, 20.0D)
        ));
        AstEntity target = AstEntity.mob(mobWithStats(MobCategory.ENEMY));

        service.applyCondition(request(target, source, ConditionType.BURNING, 1));
        service.applyCondition(request(target, source, ConditionType.BURNING, 1));

        var active = service.getActiveConditions(target);

        assertEquals(1, active.size());
        assertEquals(ConditionType.BURNING, active.get(0).type());
        assertEquals(2, active.get(0).stack());
        assertEquals(6.0D, active.get(0).snapshotPower(), 0.0001D);
    }

    @Test
    void chilledMaxStackConvertsToFrozenControl() {
        ConditionService service = conditionService();
        AstEntity target = AstEntity.mob(mobWithStats(MobCategory.ENEMY));

        service.applyCondition(request(target, null, ConditionType.CHILLED, ConditionType.CHILLED.maxStack()));

        var active = service.getActiveConditions(target);

        assertEquals(1, active.size());
        assertEquals(ConditionType.FROZEN, active.get(0).type());
        assertFalse(service.canMove(target));
        assertFalse(service.canAttack(target));
        assertFalse(service.canCastSkill(target));
        assertFalse(service.canRunAi(target));
    }

    @Test
    void vulnerableStacksDamageMultiplierAndInvulnerableBlocksDamage() {
        ConditionService service = conditionService();
        AstEntity target = AstEntity.mob(mobWithStats(MobCategory.ENEMY));

        service.applyCondition(request(target, null, ConditionType.VULNERABLE, 2));
        service.applyCondition(request(target, null, ConditionType.INVULNERABLE, 1));

        assertEquals(1.21D, service.damageTakenMultiplier(target), 0.0001D);
        assertTrue(service.isDamageImmune(target));
    }

    @Test
    void npcTargetRejectsCondition() {
        ConditionService service = conditionService();
        AstEntity target = AstEntity.mob(mobWithStats(MobCategory.NPC));

        var result = service.applyCondition(request(target, null, ConditionType.STUNNED, 1));

        assertFalse(result.success());
        assertTrue(service.getActiveConditions(target).isEmpty());
    }

    private static ConditionService conditionService() {
        return new ConditionService(
                new ConditionDisplayService(mock(ParticleDisplayService.class), new MobVanillaEffectProtectionService()),
                null
        );
    }

    private static ConditionApplyRequest request(
            AstEntity target,
            AstEntity source,
            ConditionType type,
            int stack
    ) {
        return new ConditionApplyRequest(
                target,
                source,
                type,
                type.defaultDurationTicks(),
                100.0D,
                stack,
                null,
                null,
                null,
                null,
                null,
                ConditionApplyReason.SKILL
        );
    }

    private static MobBaseStat stat(StatusType type, double value) {
        return new MobBaseStat(type.name(), value);
    }

    private static MobInstance mobWithStats(MobCategory category, MobBaseStat... stats) {
        MobTemplate template = new MobTemplate(
                1,
                "condition_service_test_mob",
                category,
                "Condition Service Test Mob",
                null,
                1,
                EntityType.ZOMBIE,
                true,
                null,
                List.of(),
                List.of(),
                null,
                MobEquipmentConfig.EMPTY,
                new ArrayList<>(List.of(stats)),
                MobShieldConfig.EMPTY,
                MobIdleConfig.defaults(),
                false,
                MobInteractionsConfig.EMPTY,
                null,
                null,
                null
        );
        return new MobInstance(
                UUID.randomUUID(),
                template,
                new Location(null, 0.0D, 64.0D, 0.0D)
        );
    }
}
