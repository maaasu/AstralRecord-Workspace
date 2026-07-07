package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageType;
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
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
            AttackType.MELEE,
            DamageType.PHYSICAL
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
            AttackType.MELEE,
            DamageType.PHYSICAL
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
            AttackType.MELEE,
            DamageType.PHYSICAL
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
        return new DamageHarness(statusService, mobCombatService, knockbackService, service);
    }

    private record DamageHarness(
        StatusService statusService,
        MobCombatService mobCombatService,
        MobKnockbackService knockbackService,
        DamageService service
    ) {
    }
}
