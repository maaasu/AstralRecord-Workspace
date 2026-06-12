package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageType;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobCombatService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class DamageServiceTest {

    @Test
    void damageImmuneMobIgnoresCustomDamage() {
        DamageService damageService = new DamageService(
                mock(StatusService.class),
                mock(MobService.class),
                mock(MobCombatService.class),
                mock(DisplayTextService.class),
                mock(PlayerSettingService.class),
                mock(ParticleDisplayService.class)
        );
        MobTemplate template = new MobTemplate(
                1,
                "npc_shopkeeper",
                MobCategory.NPC,
                "Shopkeeper",
                null,
                1,
                EntityType.VILLAGER,
                true,
                null,
                List.of(),
                List.of(),
                null,
                MobEquipmentConfig.EMPTY,
                List.of(),
                MobIdleConfig.defaults(),
                true,
                MobInteractionsConfig.EMPTY,
                null,
                null,
                null
        );
        MobInstance victim = new MobInstance(
                UUID.randomUUID(),
                template,
                new Location(null, 0.0D, 64.0D, 0.0D)
        );
        double beforeHealth = victim.currentHealth();

        DamageResult result = damageService.applyEffectDamage(null, AstEntity.mob(victim), 25.0D, DamageType.PHYSICAL);

        assertEquals(0.0D, result.finalDamage());
        assertEquals(beforeHealth, victim.currentHealth());
    }
}
