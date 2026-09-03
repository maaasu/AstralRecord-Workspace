package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.service.MobCombatService;
import io.github.maaasu.astralRecord.feature.mob.service.MobKnockbackService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DamageServiceShieldBreakMultiplierTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 9. result 反映（内部）
     * 検証契約: 従来attackはShield倍率1倍、専用overloadは3倍を一度だけ適用し、各hitのHP・threat・Shield演出を重複させない。
     */
    @Test
    void attackOverloadsApplyOneAndThreeTimesShieldDamageWithOneSideEffectPerHit() {
        AstPlayer attacker = attacker(100.0D, 1.0D);
        DamageHarness normal = damageHarness(new DamageCalculator(() -> 0.0D, () -> 100.0D));
        DamageHarness special = damageHarness(new DamageCalculator(() -> 0.0D, () -> 100.0D));
        MobInstance normalMob = shieldedMob(100.0D);
        MobInstance specialMob = shieldedMob(100.0D);
        when(normal.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());
        when(special.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());

        DamageResult normalResult = normal.service.attack(
                AstEntity.player(attacker), AstEntity.mob(normalMob), AttackType.MELEE,
                List.of(DamageComponent.defaultComponent()), DamageSource.SKILL, 1.0D
        );
        DamageResult specialResult = special.service.attack(
                AstEntity.player(attacker), AstEntity.mob(specialMob), AttackType.MELEE,
                List.of(DamageComponent.defaultComponent()), DamageSource.SKILL, 1.0D, 3.0D
        );

        assertEquals(normalResult.shieldDamage() * 3.0D, specialResult.shieldDamage(), 0.0001D);
        assertEquals(100.0D, normalMob.currentHealth(), 0.0001D);
        assertEquals(100.0D, specialMob.currentHealth(), 0.0001D);
        assertEquals(normalResult.shieldDamage(), normalMob.threatTable().snapshot().get(attacker.getBukkit().getUniqueId()), 0.0001D);
        assertEquals(specialResult.shieldDamage(), specialMob.threatTable().snapshot().get(attacker.getBukkit().getUniqueId()), 0.0001D);
        verify(normal.particleDisplayService).spawnForNearbyViewers(
                any(Location.class), argThat((Collection<Location> points) -> !points.isEmpty()),
                eq(SharedParticleDefinitions.SHIELD_HIT_DUST)
        );
        verify(special.particleDisplayService).spawnForNearbyViewers(
                any(Location.class), argThat((Collection<Location> points) -> !points.isEmpty()),
                eq(SharedParticleDefinitions.SHIELD_HIT_DUST)
        );
        verify(normal.knockbackService, never()).apply(any(), any(), anyDouble());
        verify(special.knockbackService, never()).apply(any(), any(), anyDouble());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 9. result 反映（内部）
     * 検証契約: Shieldがない対象では専用Shield倍率がHP damageへ影響せず、従来attackと同じdamageを一度だけ適用する。
     */
    @Test
    void shieldMultiplierDoesNotChangeHealthDamageWithoutShield() {
        AstPlayer attacker = attacker(100.0D, 1.0D);
        DamageHarness normal = damageHarness(new DamageCalculator(() -> 0.0D, () -> 100.0D));
        DamageHarness special = damageHarness(new DamageCalculator(() -> 0.0D, () -> 100.0D));
        MobInstance normalMob = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D);
        MobInstance specialMob = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D);
        when(normal.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());
        when(special.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());

        DamageResult normalResult = normal.service.attack(
                AstEntity.player(attacker), AstEntity.mob(normalMob), AttackType.MELEE,
                List.of(DamageComponent.defaultComponent()), DamageSource.SKILL, 1.0D
        );
        DamageResult specialResult = special.service.attack(
                AstEntity.player(attacker), AstEntity.mob(specialMob), AttackType.MELEE,
                List.of(DamageComponent.defaultComponent()), DamageSource.SKILL, 1.0D, 3.0D
        );

        assertEquals(normalResult.finalDamage(), specialResult.finalDamage(), 0.0001D);
        assertEquals(normalMob.currentHealth(), specialMob.currentHealth(), 0.0001D);
        assertEquals(0.0D, specialResult.shieldDamage(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 9. result 反映（内部）
     * 検証契約: 0 damageの専用hitはShieldを消費せず、Shield演出とthreatを発生させない。
     */
    @Test
    void zeroDamageSpecialHitDoesNotConsumeShieldOrRunShieldSideEffects() {
        AstPlayer attacker = attacker(100.0D, 1.0D);
        DamageHarness harness = damageHarness(new DamageCalculator(() -> 0.0D, () -> 100.0D));
        MobInstance mob = shieldedMob(100.0D);
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());

        DamageResult result = harness.service.attack(
                AstEntity.player(attacker), AstEntity.mob(mob), AttackType.MELEE,
                List.of(DamageComponent.defaultComponent()), DamageSource.SKILL, 0.0D, 3.0D
        );

        assertEquals(0.0D, result.finalDamage(), 0.0001D);
        assertEquals(0.0D, result.shieldDamage(), 0.0001D);
        assertEquals(100.0D, mob.currentShield(), 0.0001D);
        assertTrue(mob.threatTable().snapshot().isEmpty());
        verify(harness.particleDisplayService, never()).spawnForNearbyViewers(
                any(Location.class), argThat((Collection<Location> points) -> !points.isEmpty()),
                eq(SharedParticleDefinitions.SHIELD_HIT_DUST)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 17. ハンター アローレインの実装契約
     * 検証契約: アローレインの一撃はHP換算値やシールドブレイク加算を使わず、SHIELD_BREAKの10分の1を最低1としてShieldへ適用する。
     */
    @Test
    void shieldBreakRatioUsesOneTenthOfAttackerValueWithMinimumOne() {
        AstPlayer attacker = attacker(100.0D, 25.0D);
        DamageHarness harness = damageHarness(new DamageCalculator(() -> 0.0D, () -> 100.0D));
        MobInstance mob = shieldedMob(100.0D);
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());

        DamageResult result = harness.service.attackWithShieldBreakRatio(
                AstEntity.player(attacker), AstEntity.mob(mob), AttackType.RANGED,
                List.of(DamageComponent.defaultComponent()), DamageSource.SKILL, 0.1D
        );

        assertEquals(2.5D, result.shieldDamage(), 0.0001D);
        assertEquals(97.5D, mob.currentShield(), 0.0001D);
        assertEquals(100.0D, mob.currentHealth(), 0.0001D);

        AstPlayer lowBreakAttacker = attacker(100.0D, 5.0D);
        DamageHarness minimumHarness = damageHarness(new DamageCalculator(() -> 0.0D, () -> 100.0D));
        MobInstance minimumMob = shieldedMob(100.0D);
        when(minimumHarness.statusService.getStatus(lowBreakAttacker)).thenReturn(lowBreakAttacker.getStatusSnapshot());

        DamageResult minimumResult = minimumHarness.service.attackWithShieldBreakRatio(
                AstEntity.player(lowBreakAttacker), AstEntity.mob(minimumMob), AttackType.RANGED,
                List.of(DamageComponent.defaultComponent()), DamageSource.SKILL, 0.1D
        );

        assertEquals(1.0D, minimumResult.shieldDamage(), 0.0001D);
        assertEquals(99.0D, minimumMob.currentShield(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 1. damage 計算
     * 検証契約: 回避された専用hitは3倍指定があっても被弾者のHP・Shieldを変更しない。
     */
    @Test
    void evadedSpecialHitDoesNotConsumeShield() {
        AstPlayer attacker = attacker(1.0D, 1.0D);
        AstPlayer victim = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        StatusSnapshot victimStatus = DesignTestFixtures.statusSnapshot(Map.of(
                StatusType.MAX_HEALTH, 100.0D,
                StatusType.MAX_SHIELD, 100.0D,
                StatusType.EVASION, 100.0D
        ), 100.0D, 0.0D, 0.0D).withCurrentShield(50.0D);
        victim.setStatusSnapshot(victimStatus);
        DamageHarness harness = damageHarness(new DamageCalculator(() -> 100.0D, () -> 100.0D));
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());
        when(harness.statusService.getStatus(victim)).thenReturn(victimStatus);

        DamageResult result = harness.service.attack(
                AstEntity.player(attacker), AstEntity.player(victim), AttackType.MELEE,
                List.of(DamageComponent.defaultComponent()), DamageSource.SKILL, 1.0D, 3.0D
        );

        assertTrue(result.evaded());
        assertEquals(0.0D, result.shieldDamage(), 0.0001D);
        assertEquals(50.0D, victim.getStatusSnapshot().getCurrentShield(), 0.0001D);
        verify(harness.statusService, never()).consumeShield(victim, result.shieldDamage());
    }

    private AstPlayer attacker(double accuracy, double shieldBreak) {
        AstPlayer attacker = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
                StatusType.MAX_HEALTH, 100.0D,
                StatusType.ATTACK, 50.0D,
                StatusType.ACCURACY, accuracy,
                StatusType.CRITICAL_RATE, 0.0D,
                StatusType.SUPER_CRITICAL_RATE, 0.0D,
                StatusType.FINAL_DAMAGE_MULTIPLIER, 100.0D,
                StatusType.SHIELD_BREAK, shieldBreak
        ), 100.0D, 0.0D, 0.0D));
        return attacker;
    }

    private MobInstance shieldedMob(double shield) {
        MobInstance mob = DesignTestFixtures.mobInstance(
                100.0D, 0.0D, 0.0D, new MobShieldConfig(true, shield)
        );
        mob.currentLocation(new Location(mock(World.class), 0.0D, 64.0D, 0.0D));
        return mob;
    }

    private DamageHarness damageHarness(DamageCalculator calculator) {
        StatusService statusService = mock(StatusService.class);
        MobKnockbackService knockbackService = mock(MobKnockbackService.class);
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        DamageService service = new DamageService(
                statusService,
                mock(MobService.class),
                mock(MobCombatService.class),
                knockbackService,
                mock(DisplayTextService.class),
                mock(PlayerSettingService.class),
                particleDisplayService,
                null,
                null,
                calculator
        );
        return new DamageHarness(statusService, knockbackService, particleDisplayService, service);
    }

    private record DamageHarness(
            StatusService statusService,
            MobKnockbackService knockbackService,
            ParticleDisplayService particleDisplayService,
            DamageService service
    ) {
    }
}
