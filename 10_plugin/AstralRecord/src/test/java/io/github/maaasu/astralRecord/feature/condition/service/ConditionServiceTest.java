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
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ConditionServiceTest {

    @Test
    void bossAmplifierResistanceHalvesVulnerableEffect() {
        ConditionDisplayService displayService = mock(ConditionDisplayService.class);
        ConditionService service = new ConditionService(displayService, null);
        MobTemplate template = new MobTemplate(
            1,
            "test_boss",
            MobCategory.BOSS,
            "Test Boss",
            null,
            1,
            EntityType.IRON_GOLEM,
            false,
            null,
            List.of(),
            List.of(),
            null,
            MobEquipmentConfig.EMPTY,
            List.of(new MobBaseStat(StatusType.MAX_HEALTH.name(), 100.0D)),
            MobShieldConfig.EMPTY,
            MobIdleConfig.defaults(),
            false,
            MobInteractionsConfig.EMPTY,
            null,
            null,
            null
        );
        MobInstance mob = new MobInstance(
            UUID.randomUUID(),
            template,
            new Location(null, 0.0D, 0.0D, 0.0D)
        );
        AstEntity target = AstEntity.mob(mob);

        service.applyCondition(new ConditionApplyRequest(
            target,
            null,
            ConditionType.VULNERABLE,
            ConditionType.VULNERABLE.defaultDurationTicks(),
            100.0D,
            1,
            null,
            null,
            null,
            null,
            null,
            ConditionApplyReason.SKILL
        ));

        assertEquals(1.05D, service.damageTakenMultiplier(target), 0.0001D);
    }
}
