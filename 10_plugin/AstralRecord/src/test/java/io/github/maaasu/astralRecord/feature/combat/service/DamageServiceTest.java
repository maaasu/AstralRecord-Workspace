package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageType;
import io.github.maaasu.astralRecord.feature.mob.model.MobBaseStat;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobCombatService;
import io.github.maaasu.astralRecord.feature.mob.service.MobKnockbackService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DamageServiceTest {

    @Test
    void damageImmuneMobIgnoresCustomDamage() {
        DamageService damageService = new DamageService(
                mock(StatusService.class),
                mock(MobService.class),
                mock(MobCombatService.class),
                mock(MobKnockbackService.class),
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
                MobShieldConfig.EMPTY,
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

    @Test
    void shieldedMobConsumesShieldBeforeHealth() {
        DamageService damageService = damageService();
        MobInstance victim = shieldedMob(100.0D, 5.0D);
        double beforeHealth = victim.currentHealth();

        DamageResult result = damageService.applyEffectDamage(null, AstEntity.mob(victim), 20.0D, DamageType.PHYSICAL);

        assertEquals(0.0D, result.finalDamage());
        assertEquals(2.0D, result.shieldDamage());
        assertEquals(3.0D, victim.currentShield());
        assertEquals(beforeHealth, victim.currentHealth());
    }

    @Test
    void shieldedMobIgnoresDamageBelowOneShieldUnit() {
        DamageService damageService = damageService();
        MobInstance victim = shieldedMob(100.0D, 5.0D);
        double beforeHealth = victim.currentHealth();

        DamageResult result = damageService.applyEffectDamage(null, AstEntity.mob(victim), 9.0D, DamageType.PHYSICAL);

        assertEquals(0.0D, result.finalDamage());
        assertEquals(0.0D, result.shieldDamage());
        assertEquals(5.0D, victim.currentShield());
        assertEquals(beforeHealth, victim.currentHealth());
    }

    @Test
    void meleeDamageKeepsCurrentKnockbackAmount() {
        MobKnockbackService knockbackService = mock(MobKnockbackService.class);
        DamageService damageService = damageService(knockbackService);
        MobInstance attacker = shieldedMob(100.0D, 0.0D);
        MobInstance victim = shieldedMob(100.0D, 0.0D);

        damageService.applyDamage(
                AstEntity.mob(attacker),
                AstEntity.mob(victim),
                20.0D,
                AttackType.MELEE,
                DamageType.PHYSICAL
        );

        verify(knockbackService).apply(any(AstEntity.class), any(AstEntity.class), eq(1.0D));
    }

    @Test
    void rangedDamageUsesReducedKnockback() {
        MobKnockbackService knockbackService = mock(MobKnockbackService.class);
        DamageService damageService = damageService(knockbackService);
        MobInstance attacker = shieldedMob(100.0D, 0.0D);
        MobInstance victim = shieldedMob(100.0D, 0.0D);

        damageService.applyDamage(
                AstEntity.mob(attacker),
                AstEntity.mob(victim),
                20.0D,
                AttackType.RANGED,
                DamageType.PHYSICAL
        );

        verify(knockbackService).apply(any(AstEntity.class), any(AstEntity.class), eq(0.55D));
    }

    @Test
    void magicDamageUsesReducedKnockback() {
        MobKnockbackService knockbackService = mock(MobKnockbackService.class);
        DamageService damageService = damageService(knockbackService);
        MobInstance attacker = shieldedMob(100.0D, 0.0D);
        MobInstance victim = shieldedMob(100.0D, 0.0D);

        damageService.applyEffectDamage(AstEntity.mob(attacker), AstEntity.mob(victim), 20.0D, DamageType.PHYSICAL);

        verify(knockbackService).apply(any(AstEntity.class), any(AstEntity.class), eq(0.4D));
    }

    @Test
    void shieldOnlyDamageDoesNotApplyKnockback() {
        MobKnockbackService knockbackService = mock(MobKnockbackService.class);
        DamageService damageService = damageService(knockbackService);
        MobInstance attacker = shieldedMob(100.0D, 0.0D);
        MobInstance victim = shieldedMob(100.0D, 5.0D);

        damageService.applyDamage(
                AstEntity.mob(attacker),
                AstEntity.mob(victim),
                20.0D,
                AttackType.MELEE,
                DamageType.PHYSICAL
        );

        verify(knockbackService, never()).apply(any(AstEntity.class), any(AstEntity.class), anyDouble());
    }

    private DamageService damageService() {
        return damageService(mock(MobKnockbackService.class));
    }

    private DamageService damageService(MobKnockbackService knockbackService) {
        return new DamageService(
                mock(StatusService.class),
                mock(MobService.class),
                mock(MobCombatService.class),
                knockbackService,
                mock(DisplayTextService.class),
                mock(PlayerSettingService.class),
                mock(ParticleDisplayService.class)
        );
    }

    private MobInstance shieldedMob(double maxHealth, double maxShield) {
        MobTemplate template = new MobTemplate(
                1,
                "shielded_mob",
                MobCategory.ENEMY,
                "Shielded Mob",
                null,
                1,
                EntityType.ZOMBIE,
                true,
                null,
                List.of(),
                List.of(),
                null,
                MobEquipmentConfig.EMPTY,
                List.of(new MobBaseStat(StatusType.MAX_HEALTH.name(), maxHealth)),
                new MobShieldConfig(true, maxShield),
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
