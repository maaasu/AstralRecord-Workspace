package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobCombatService;
import io.github.maaasu.astralRecord.feature.mob.service.MobKnockbackService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DamageServiceMobDesignTest extends MockBukkitTestBase {

    @Test
    void playerDamageToMobReducesHealthAddsThreatAndSwitchesToAggro() {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        MobInstance mob = DesignTestFixtures.mobInstance(10.0D, 0.0D, 0.0D);
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());

        DamageResult result = harness.service.applyDamage(
            AstEntity.player(attacker),
            AstEntity.mob(mob),
            6.0D,
            AttackType.MELEE
        );

        assertEquals(6.0D, result.finalDamage(), 0.0001D);
        assertEquals(4.0D, mob.currentHealth(), 0.0001D);
        assertEquals(6.0D, mob.threatTable().snapshot().get(attacker.getBukkit().getUniqueId()), 0.0001D);
        assertEquals(MobState.AGGRO, mob.state());
        assertEquals(attacker.getBukkit().getUniqueId(), mob.targetId());
        assertEquals(attacker.getBukkit().getUniqueId(), mob.lastAttackerUuid());
        verify(harness.knockbackService).apply(any(AstEntity.class), any(AstEntity.class), eq(1.0D));
    }

    @Test
    void finalDamageMultiplierScalesDamageBeforeApplyingIt() {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.FINAL_DAMAGE_MULTIPLIER, 150.0D
        ), 100.0D, 0.0D, 0.0D));
        MobInstance mob = DesignTestFixtures.mobInstance(10.0D, 0.0D, 0.0D);
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());

        DamageResult result = harness.service.applyDamage(
            AstEntity.player(attacker),
            AstEntity.mob(mob),
            6.0D,
            AttackType.MELEE
        );

        assertEquals(9.0D, result.finalDamage(), 0.0001D);
        assertEquals(1.0D, mob.currentHealth(), 0.0001D);
    }

    @Test
    void explicitZeroFinalDamageMultiplierDealsNoDamage() {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.FINAL_DAMAGE_MULTIPLIER, 0.0D
        ), 100.0D, 0.0D, 0.0D));
        MobInstance mob = DesignTestFixtures.mobInstance(10.0D, 0.0D, 0.0D);
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());

        DamageResult result = harness.service.applyDamage(
            AstEntity.player(attacker),
            AstEntity.mob(mob),
            6.0D,
            AttackType.MELEE
        );

        assertEquals(0.0D, result.finalDamage(), 0.0001D);
        assertEquals(10.0D, mob.currentHealth(), 0.0001D);
    }

    @Test
    void lifeStealRecoversHpFromActualHealthDamageOnly() {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.LIFE_STEAL, 25.0D
        ), 100.0D, 0.0D, 0.0D));
        MobInstance mob = DesignTestFixtures.mobInstance(10.0D, 0.0D, 0.0D);
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());

        harness.service.applyDamage(AstEntity.player(attacker), AstEntity.mob(mob), 6.0D, AttackType.MELEE);

        verify(harness.statusService).recoverHp(attacker, 1.5D);
    }

    @Test
    void lethalPlayerDamageMarksMobDeadAndDelegatesDeathHandling() {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        MobInstance mob = DesignTestFixtures.mobInstance(10.0D, 0.0D, 0.0D);
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());
        when(harness.mobCombatService.handleDeath(mob)).thenReturn(List.of());

        DamageResult result = harness.service.applyDamage(
            AstEntity.player(attacker),
            AstEntity.mob(mob),
            12.0D,
            AttackType.MELEE
        );

        assertEquals(12.0D, result.finalDamage(), 0.0001D);
        assertEquals(0.0D, mob.currentHealth(), 0.0001D);
        assertEquals(MobState.DEAD, mob.state());
        verify(harness.mobCombatService).handleDeath(mob);
    }

    @Test
    void shieldDamageConsumesShieldAndAddsThreatBeforeHealthDamage() {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        MobInstance mob = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D, new MobShieldConfig(true, 3.0D));
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());

        DamageResult result = harness.service.applyDamage(
            AstEntity.player(attacker),
            AstEntity.mob(mob),
            50.0D,
            AttackType.MELEE
        );

        assertEquals(0.0D, result.finalDamage(), 0.0001D);
        assertEquals(3.0D, result.shieldDamage(), 0.0001D);
        assertEquals(0.0D, mob.currentShield(), 0.0001D);
        assertEquals(100.0D, mob.currentHealth(), 0.0001D);
        assertEquals(3.0D, mob.threatTable().snapshot().get(attacker.getBukkit().getUniqueId()), 0.0001D);
        assertEquals(MobState.AGGRO, mob.state());
        assertEquals(attacker.getBukkit().getUniqueId(), mob.targetId());
        verify(harness.knockbackService, never()).apply(any(AstEntity.class), any(AstEntity.class), eq(1.0D));
    }

    @Test
    void lowDamageConsumesAtLeastOneShieldAndBatchesShieldParticles() {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        MobInstance mob = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D, new MobShieldConfig(true, 3.0D));
        World world = mock(World.class);
        mob.currentLocation(new Location(world, 0.0D, 64.0D, 0.0D));
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());

        DamageResult result = harness.service.applyDamage(
            AstEntity.player(attacker),
            AstEntity.mob(mob),
            1.0D,
            AttackType.MELEE
        );

        assertEquals(0.0D, result.finalDamage(), 0.0001D);
        assertEquals(1.0D, result.shieldDamage(), 0.0001D);
        assertEquals(2.0D, mob.currentShield(), 0.0001D);
        assertEquals(100.0D, mob.currentHealth(), 0.0001D);
        verify(harness.particleDisplayService).spawnForNearbyViewers(
            any(Location.class),
            argThat((Collection<Location> locations) -> locations.size() == 18),
            eq(SharedParticleDefinitions.SHIELD_HIT_DUST)
        );
    }

    @Test
    void unmanagedAttackerUsesDamageCapturedBeforeEventCancellation() {
        DamageHarness harness = damageHarness();
        MobInstance mob = DesignTestFixtures.mobInstance(10.0D, 0.0D, 0.0D);
        Entity damager = mock(Entity.class);
        Entity victim = mock(Entity.class);
        UUID damagerId = UUID.randomUUID();
        UUID victimId = UUID.randomUUID();
        when(damager.getUniqueId()).thenReturn(damagerId);
        when(victim.getUniqueId()).thenReturn(victimId);
        when(harness.mobService.getInstanceByEntity(damagerId)).thenReturn(null);
        when(harness.mobService.getInstanceByEntity(victimId)).thenReturn(mob);

        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        AtomicReference<Double> eventDamage = new AtomicReference<>(6.0D);
        when(event.getDamager()).thenReturn(damager);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDamage()).thenAnswer(ignored -> eventDamage.get());
        doAnswer(invocation -> {
            eventDamage.set(invocation.getArgument(0));
            return null;
        }).when(event).setDamage(anyDouble());

        harness.service.handleEntityDamage(event);

        assertEquals(4.0D, mob.currentHealth(), 0.0001D);
        verify(event).setDamage(0.0D);
        verify(event).setCancelled(true);
    }

    @Test
    void mobEntityExposesRuntimeMaxHealthAfterScaling() {
        MobInstance mob = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D);
        mob.maxHealth(250.0D);
        mob.currentHealth(250.0D);
        AstEntity entity = AstEntity.mob(mob);

        assertEquals(250.0D, mob.maxHealth(), 0.0001D);
        assertEquals(250.0D, entity.maxHealth(), 0.0001D);
        assertEquals(250.0D, entity.statValue(StatusType.MAX_HEALTH), 0.0001D);
    }

    @Test
    void poisonConditionDamageNeverReducesHealthBelowOneAndCannotCrit() {
        DamageHarness harness = damageHarness();
        MobInstance mob = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D);
        mob.currentHealth(1.2D);

        DamageResult result = harness.service.applyConditionDamage(
            null,
            AstEntity.mob(mob),
            5.0D,
            ConditionType.POISON
        );

        assertEquals(0.2D, result.finalDamage(), 0.0001D);
        assertEquals(1.0D, mob.currentHealth(), 0.0001D);
        assertEquals(false, result.critical());
    }

    private AstPlayer attacker() {
        AstPlayer attacker = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D
        ), 100.0D, 0.0D, 0.0D));
        return attacker;
    }

    private DamageHarness damageHarness() {
        StatusService statusService = mock(StatusService.class);
        MobService mobService = mock(MobService.class);
        MobCombatService mobCombatService = mock(MobCombatService.class);
        MobKnockbackService knockbackService = mock(MobKnockbackService.class);
        DisplayTextService displayTextService = mock(DisplayTextService.class);
        PlayerSettingService playerSettingService = mock(PlayerSettingService.class);
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        DamageService service = new DamageService(
            statusService,
            mobService,
            mobCombatService,
            knockbackService,
            displayTextService,
            playerSettingService,
            particleDisplayService
        );
        return new DamageHarness(
            statusService,
            mobService,
            mobCombatService,
            knockbackService,
            particleDisplayService,
            service
        );
    }

    private record DamageHarness(
        StatusService statusService,
        MobService mobService,
        MobCombatService mobCombatService,
        MobKnockbackService knockbackService,
        ParticleDisplayService particleDisplayService,
        DamageService service
    ) {
    }
}
