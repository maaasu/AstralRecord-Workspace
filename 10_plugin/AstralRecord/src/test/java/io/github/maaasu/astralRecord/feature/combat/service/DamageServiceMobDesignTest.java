package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
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
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DamageServiceMobDesignTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 1. damage 計算
     * 検証契約: direct damage modifierはshield反映前の通常damageへ倍率を適用する。
     */
    @Test
    void directDamageModifierScalesCalculatedDamageBeforeApplication() {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.ATTACK, 10.0D,
            StatusType.ACCURACY, 100.0D,
            StatusType.FINAL_DAMAGE_MULTIPLIER, 100.0D
        ), 100.0D, 0.0D, 0.0D));
        MobInstance mob = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D);
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());
        harness.service.setDirectDamageModifier((source, victim, attackType, damageSource, result) ->
                DirectDamageModification.multiplier(0.5D));

        DamageResult result = harness.service.attack(
            AstEntity.player(attacker),
            AstEntity.mob(mob),
            AttackType.MELEE
        );

        assertEquals(5.0D, result.finalDamage(), 0.0001D);
        assertEquals(95.0D, mob.currentHealth(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 10. ソードマン・ブレードカウンターの実装契約
     * 検証契約: direct damage modifierの後処理は元hitのHP反映と表示処理が完了した後に実行する。
     */
    @Test
    void directDamageModifierRunsPostHitActionAfterOriginalDamageApplication() {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
                StatusType.MAX_HEALTH, 100.0D,
                StatusType.ATTACK, 10.0D,
                StatusType.ACCURACY, 100.0D,
                StatusType.FINAL_DAMAGE_MULTIPLIER, 100.0D
        ), 100.0D, 0.0D, 0.0D));
        MobInstance mob = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D);
        AtomicReference<Double> healthSeenByPostHit = new AtomicReference<>();
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());
        harness.service.setDirectDamageModifier((source, victim, attackType, damageSource, result) ->
                new DirectDamageModification(0.5D, () -> healthSeenByPostHit.set(victim.currentHealth())));

        DamageResult result = harness.service.attack(
                AstEntity.player(attacker),
                AstEntity.mob(mob),
                AttackType.MELEE
        );

        assertEquals(5.0D, result.finalDamage(), 0.0001D);
        assertEquals(95.0D, healthSeenByPostHit.get(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 9. result 反映（内部）
     * 検証契約: playerからMobへのHP damageをcurrentHealthへ反映しthreatを加算してaggroへ移行する。
     */
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
        verify(harness.knockbackService).apply(any(AstEntity.class), any(AstEntity.class), eq(0.55D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 9. result 反映（内部）
     * 検証契約: Mobへの有効なHPダメージでは、対象エンティティの被ダメージ音と既存のプレイヤー被ダメージ音を両方再生する。
     */
    @Test
    void pigDamagePlaysPigAndPlayerHurtSounds() {
        assertMobDamageSounds(Sound.ENTITY_PIG_HURT);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 9. result 反映（内部）
     * 検証契約: スケルトン系Mobへの有効なHPダメージでは、スケルトンの被ダメージ音と既存のプレイヤー被ダメージ音を両方再生する。
     */
    @Test
    void skeletonDamagePlaysSkeletonAndPlayerHurtSounds() {
        assertMobDamageSounds(Sound.ENTITY_SKELETON_HURT);
    }

    private void assertMobDamageSounds(Sound targetHurtSound) {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        MobInstance mob = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D);
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());

        World world = mock(World.class);
        Location location = new Location(world, 0.0D, 64.0D, 0.0D);
        LivingEntity entity = mock(LivingEntity.class);
        UUID entityId = UUID.randomUUID();
        when(entity.getLocation()).thenReturn(location);
        when(entity.getHeight()).thenReturn(2.0D);
        when(entity.getHurtSound()).thenReturn(targetHurtSound);
        mob.bindEntity(entityId, 1, location);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getEntity(entityId)).thenReturn(entity);

            DamageResult result = harness.service.applyDamage(
                AstEntity.player(attacker),
                AstEntity.mob(mob),
                6.0D,
                AttackType.MELEE
            );

            assertEquals(6.0D, result.finalDamage(), 0.0001D);
        }

        verify(world).playSound(
            any(Location.class),
            same(targetHurtSound),
            eq(SoundCategory.PLAYERS),
            eq(0.75F),
            eq(1.0F)
        );
        verify(world).playSound(
            any(Location.class),
            eq(Sound.ENTITY_PLAYER_HURT),
            eq(SoundCategory.PLAYERS),
            eq(0.75F),
            eq(1.0F)
        );
        verify(world, never()).playSound(
            any(Location.class),
            eq(Sound.ENTITY_GENERIC_HURT),
            eq(SoundCategory.PLAYERS),
            eq(0.75F),
            eq(1.0F)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 1. damage 計算
     * 検証契約: FINAL_DAMAGE_MULTIPLIERで最終反映damageを比例scaleする。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 1. damage 計算
     * 検証契約: Mobの実行時outgoingDamageMultiplierを通常攻撃とskillに共通する最終倍率として一度だけ適用する。
     */
    @Test
    void mobRuntimeOutgoingMultiplierScalesFinalDamageOnce() {
        DamageHarness harness = damageHarness();
        MobInstance attacker = DesignTestFixtures.mobInstanceWithAttack(100.0D, 10.0D, 0.0D, 0.0D);
        MobInstance normalAttackVictim = DesignTestFixtures.mobInstance(40.0D, 0.0D, 0.0D);
        MobInstance skillVictim = DesignTestFixtures.mobInstance(40.0D, 0.0D, 0.0D);
        attacker.outgoingDamageMultiplier(1.5D);

        DamageResult normalAttack = harness.service.attack(
            AstEntity.mob(attacker),
            AstEntity.mob(normalAttackVictim),
            AttackType.MELEE
        );
        DamageResult skill = harness.service.attack(
            AstEntity.mob(attacker),
            AstEntity.mob(skillVictim),
            AttackType.MELEE,
            List.of(DamageComponent.defaultComponent()),
            DamageSource.SKILL
        );

        assertEquals(15.0D, normalAttack.finalDamage(), 0.0001D);
        assertEquals(15.0D, skill.finalDamage(), 0.0001D);
        assertEquals(25.0D, normalAttackVictim.currentHealth(), 0.0001D);
        assertEquals(25.0D, skillVictim.currentHealth(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 1. damage 計算
     * 検証契約: 明示FINAL_DAMAGE_MULTIPLIER=0でHP/shield damageを発生させない。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 9. result 反映（内部）
     * 検証契約: LIFE_STEAL回復量をshield吸収前の計算値でなく実HP damageだけから算出する。
     */
    @Test
    void lifeStealRecoversHpFromActualHealthDamageOnly() {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.LIFE_STEAL, 25.0D,
            StatusType.FINAL_DAMAGE_MULTIPLIER, 100.0D
        ), 100.0D, 0.0D, 0.0D));
        MobInstance mob = DesignTestFixtures.mobInstance(10.0D, 0.0D, 0.0D);
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());

        harness.service.applyDamage(AstEntity.player(attacker), AstEntity.mob(mob), 6.0D, AttackType.MELEE);

        verify(harness.statusService).recoverHp(attacker, 1.5D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 9. result 反映（内部）
     * 検証契約: playerの致死damageでMob HPを0にしdead状態へしてMob/boss death handlerへ委譲する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 9. result 反映（内部）
     * 検証契約: 死亡済みMobへの後続damageでは状態と報酬配布を再処理しない。
     */
    @Test
    void damageToDeadMobDoesNotProcessDeathTwice() {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        MobInstance mob = DesignTestFixtures.mobInstance(10.0D, 0.0D, 0.0D);
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());
        when(harness.mobCombatService.handleDeath(mob)).thenReturn(List.of());

        harness.service.applyDamage(
            AstEntity.player(attacker),
            AstEntity.mob(mob),
            12.0D,
            AttackType.MELEE
        );
        harness.service.applyDamage(
            AstEntity.player(attacker),
            AstEntity.mob(mob),
            12.0D,
            AttackType.MELEE
        );

        assertEquals(0.0D, mob.currentHealth(), 0.0001D);
        assertEquals(MobState.DEAD, mob.state());
        verify(harness.mobCombatService).handleDeath(mob);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 9. result 反映（内部）
     * 検証契約: shield有効hitはshieldだけを消費してthreatを加え同hitのHP damageを0にする。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/14_0-概要.md
     * 章・見出し: # 14_0-概要 > ## 5. 固定HPダメージとShield
     * 検証契約: シールドリチャージ設定済みプレイヤーは、シールドが残っている被ダメージでも待機を更新する。
     */
    @Test
    void configuredPlayerRechargeRestartsOnShieldDamage() {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        AstPlayer victim = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        victim.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D, StatusType.MAX_SHIELD, 30.0D
        ), 100.0D, 0.0D, 0.0D).withCurrentShield(30.0D));
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());
        when(harness.statusService.getStatus(victim)).thenReturn(victim.getStatusSnapshot());
        when(harness.statusService.hasConfiguredShieldRecharge(victim)).thenReturn(true);

        harness.service.applyDamage(AstEntity.player(attacker), AstEntity.player(victim), 10.0D, AttackType.MELEE);

        verify(harness.statusService).startShieldRechargeWhileRetained(eq(victim), anyLong());
        verify(harness.statusService, never()).startShieldRecharge(eq(victim), anyLong());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/14_0-概要.md
     * 章・見出し: # 14_0-概要 > ## 5. 固定HPダメージとShield
     * 検証契約: 管理者向け再充填パッシブがあっても、シールド破壊時は通常の全回復経路だけを開始する。
     */
    @Test
    void configuredPlayerBreakStartsFullRecoveryInsteadOfRetainedRecharge() {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        AstPlayer victim = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        victim.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D, StatusType.MAX_SHIELD, 30.0D
        ), 100.0D, 0.0D, 0.0D).withCurrentShield(30.0D));
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());
        when(harness.statusService.getStatus(victim)).thenReturn(victim.getStatusSnapshot());
        when(harness.statusService.hasConfiguredShieldRecharge(victim)).thenReturn(true);

        DamageResult result = harness.service.applyDamage(
            AstEntity.player(attacker),
            AstEntity.player(victim),
            500.0D,
            AttackType.MELEE
        );

        assertEquals(true, result.shieldBroken());
        verify(harness.statusService).startShieldRecharge(eq(victim), anyLong());
        verify(harness.statusService, never()).startShieldRechargeWhileRetained(eq(victim), anyLong());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 1. damage 計算
     * 検証契約: 直接攻撃のHP固定damageを通常damageへ加算し、life stealの算出元から除外する。
     */
    @Test
    void directAttackAddsFixedHealthDamageWithoutLifeSteal() {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.ATTACK, 10.0D,
            StatusType.ACCURACY, 100.0D,
            StatusType.FINAL_DAMAGE_MULTIPLIER, 100.0D,
            StatusType.FIXED_HEALTH_DAMAGE, 7.0D,
            StatusType.LIFE_STEAL, 50.0D
        ), 50.0D, 0.0D, 0.0D));
        MobInstance mob = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D);
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());

        DamageResult result = harness.service.attack(
            AstEntity.player(attacker),
            AstEntity.mob(mob),
            AttackType.MELEE
        );

        assertEquals(17.0D, result.finalDamage(), 0.0001D);
        assertEquals(7.0D, result.fixedHealthDamage(), 0.0001D);
        assertEquals(83.0D, mob.currentHealth(), 0.0001D);
        verify(harness.statusService).recoverHp(attacker, 5.0D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 9. result 反映（内部）
     * 検証契約: shield破壊hitでは固定HP damageを遮断し、recharge開始後に同hitの延長抽選を適用する。
     */
    @Test
    void shieldBreakBlocksFixedDamageAndStartsThenExtendsRecharge() {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.ATTACK, 50.0D,
            StatusType.ACCURACY, 100.0D,
            StatusType.FINAL_DAMAGE_MULTIPLIER, 100.0D,
            StatusType.FIXED_HEALTH_DAMAGE, 20.0D,
            StatusType.SHIELD_RECHARGE_DELAY_CHANCE, 100.0D,
            StatusType.SHIELD_RECHARGE_DELAY_SECONDS, 10.0D
        ), 100.0D, 0.0D, 0.0D));
        MobInstance mob = DesignTestFixtures.mobInstance(
            100.0D,
            0.0D,
            0.0D,
            new MobShieldConfig(true, 3.0D, 20.0D, 50.0D)
        );
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());

        DamageResult result = harness.service.attack(
            AstEntity.player(attacker),
            AstEntity.mob(mob),
            AttackType.MELEE
        );

        assertEquals(0.0D, result.finalDamage(), 0.0001D);
        assertEquals(0.0D, result.fixedHealthDamage(), 0.0001D);
        assertEquals(100.0D, mob.currentHealth(), 0.0001D);
        assertEquals(30_000L,
            mob.shieldRechargeState().completesAtMs() - mob.shieldRechargeState().startedAtMs());
        assertEquals(50.0D, mob.shieldRechargeState().rechargeAmount(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### Shield リチャージ
     * 検証契約: 攻撃処理開始時に期限到来済みリチャージを確定し、回復済みshieldへ同hitを適用する。
     */
    @Test
    void expiredRechargeCompletesBeforeTheIncomingHit() {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.ATTACK, 50.0D,
            StatusType.ACCURACY, 100.0D,
            StatusType.FINAL_DAMAGE_MULTIPLIER, 100.0D
        ), 100.0D, 0.0D, 0.0D));
        MobInstance mob = DesignTestFixtures.mobInstance(
            100.0D,
            0.0D,
            0.0D,
            new MobShieldConfig(true, 3.0D, 20.0D, 3.0D)
        );
        mob.currentShield(0.0D, 0L);
        mob.startShieldRecharge(0L, 0L);
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());

        DamageResult result = harness.service.attack(
            AstEntity.player(attacker),
            AstEntity.mob(mob),
            AttackType.MELEE
        );

        assertEquals(3.0D, result.shieldDamage(), 0.0001D);
        assertEquals(0.0D, result.finalDamage(), 0.0001D);
        assertEquals(100.0D, mob.currentHealth(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 9. result 反映（内部）
     * 検証契約: HP1のnonLethal Mobへ実HP damageがないhitでは進行中リチャージを延長しない。
     */
    @Test
    void nonLethalMobAtOneHealthDoesNotExtendRechargeWithoutEffectiveHealthDamage() {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.ATTACK, 10.0D,
            StatusType.ACCURACY, 100.0D,
            StatusType.FINAL_DAMAGE_MULTIPLIER, 100.0D,
            StatusType.SHIELD_RECHARGE_DELAY_CHANCE, 100.0D,
            StatusType.SHIELD_RECHARGE_DELAY_SECONDS, 10.0D
        ), 100.0D, 0.0D, 0.0D));
        MobInstance mob = DesignTestFixtures.mobInstance(
            100.0D,
            0.0D,
            0.0D,
            new MobShieldConfig(true, 3.0D, 60.0D, 3.0D)
        );
        mob.nonLethal(true);
        mob.currentHealth(1.0D);
        long nowMs = System.currentTimeMillis();
        mob.currentShield(0.0D, nowMs);
        mob.startShieldRecharge(nowMs, 60_000L);
        long completesAtMs = mob.shieldRechargeState().completesAtMs();
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());

        harness.service.attack(AstEntity.player(attacker), AstEntity.mob(mob), AttackType.MELEE);

        assertEquals(1.0D, mob.currentHealth(), 0.0001D);
        assertEquals(completesAtMs, mob.shieldRechargeState().completesAtMs());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 7. condition damage
     * 検証契約: condition DoTは有効shieldを参照・消費せずHPへ直通する。
     */
    @Test
    void conditionDamageBypassesActiveShield() {
        DamageHarness harness = damageHarness();
        MobInstance mob = DesignTestFixtures.mobInstance(
            100.0D,
            0.0D,
            0.0D,
            new MobShieldConfig(true, 3.0D)
        );

        DamageResult result = harness.service.applyConditionDamage(
            null,
            AstEntity.mob(mob),
            5.0D,
            ConditionType.BURNING
        );

        assertEquals(5.0D, result.finalDamage(), 0.0001D);
        assertEquals(3.0D, mob.currentShield(), 0.0001D);
        assertEquals(95.0D, mob.currentHealth(), 0.0001D);
        assertEquals(null, mob.shieldRechargeState());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 9. result 反映（内部）
     * 検証契約: 正の微小damageでもshieldを最低1消費しshield particleをbatch表示する。
     */
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

    /**
     * 通常会心が共通パーティクル定義と専用サウンドを使用することを確認します。
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 9. result 反映（内部）
     * 検証契約: 通常criticalでshared CRITICAL_HIT_CRIT particleと専用attack crit soundを使う。
     */
    @Test
    void normalCriticalUsesSharedParticleAndDedicatedSound() {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.CRITICAL_RATE, 100.0D,
            StatusType.CRITICAL_DAMAGE, 150.0D,
            StatusType.SUPER_CRITICAL_RATE, 0.0D,
            StatusType.FINAL_DAMAGE_MULTIPLIER, 100.0D
        ), 100.0D, 0.0D, 0.0D));
        MobInstance mob = DesignTestFixtures.mobInstance(1000.0D, 0.0D, 0.0D);
        World world = mock(World.class);
        mob.currentLocation(new Location(world, 0.0D, 64.0D, 0.0D));
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());

        DamageResult result = harness.service.applyDamage(
            AstEntity.player(attacker),
            AstEntity.mob(mob),
            10.0D,
            AttackType.MELEE
        );

        assertEquals(true, result.critical());
        assertEquals(false, result.superStarCritical());
        verify(harness.particleDisplayService).spawnForNearbyViewers(
            any(Location.class),
            eq(SharedParticleDefinitions.CRITICAL_HIT_CRIT)
        );
        verify(world).playSound(
            any(Location.class),
            eq(Sound.ENTITY_PLAYER_ATTACK_CRIT),
            eq(SoundCategory.PLAYERS),
            eq(0.9F),
            eq(1.0F)
        );
    }

    /**
     * 超星会心が重ね合わせた共通パーティクル定義と専用サウンドを使用することを確認します。
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 9. result 反映（内部）
     * 検証契約: 超星criticalで2種shared particleを重ね専用twinkle soundを使う。
     */
    @Test
    void superStarCriticalUsesLayeredSharedParticlesAndDedicatedSound() {
        DamageHarness harness = damageHarness();
        AstPlayer attacker = attacker();
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.CRITICAL_RATE, 0.0D,
            StatusType.SUPER_CRITICAL_RATE, 100.0D,
            StatusType.SUPER_CRITICAL_DAMAGE, 30.0D,
            StatusType.FINAL_DAMAGE_MULTIPLIER, 100.0D
        ), 100.0D, 0.0D, 0.0D));
        MobInstance mob = DesignTestFixtures.mobInstance(1000.0D, 0.0D, 0.0D);
        World world = mock(World.class);
        mob.currentLocation(new Location(world, 0.0D, 64.0D, 0.0D));
        when(harness.statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());

        DamageResult result = harness.service.applyDamage(
            AstEntity.player(attacker),
            AstEntity.mob(mob),
            10.0D,
            AttackType.MELEE
        );

        assertEquals(false, result.critical());
        assertEquals(true, result.superStarCritical());
        verify(harness.particleDisplayService).spawnForNearbyViewers(
            any(Location.class),
            eq(SharedParticleDefinitions.SUPER_STAR_CRITICAL_BURST_END_ROD)
        );
        verify(harness.particleDisplayService).spawnForNearbyViewers(
            any(Location.class),
            eq(SharedParticleDefinitions.SUPER_STAR_CRITICAL_IMPACT)
        );
        verify(world).playSound(
            any(Location.class),
            eq(Sound.ENTITY_FIREWORK_ROCKET_TWINKLE),
            eq(SoundCategory.PLAYERS),
            eq(1.0F),
            eq(1.2F)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 4. Bukkit damage 変換
     * 検証契約: unmanaged attackerのevent damageをcancel/0化前にcaptureしFIXED applyDamageへ渡す。
     */
    @Test
    void unmanagedAttackerUsesDamageCapturedBeforeEventCancellation() {
        DamageHarness harness = damageHarness();
        MobInstance mob = DesignTestFixtures.mobInstance(10.0D, 0.0D, 0.0D);
        Entity damager = mock(Entity.class);
        Entity victim = mock(Entity.class);
        when(harness.mobService.getInstanceByEntity(damager)).thenReturn(null);
        when(harness.mobService.getInstanceByEntity(victim)).thenReturn(mob);

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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/14_1-モデル定義.md
     * 章・見出し: # 14_1-モデル定義 > ## 7. unified entity
     * 検証契約: AstEntity.mobのmaxHealthがtemplate基礎値でなくruntime level scaling後Mob値を返す。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 7. condition damage
     * 検証契約: poison DoTはHP 0まで適用でき、通常/超星criticalを判定しない。
     */
    @Test
    void poisonConditionDamageCanReduceHealthToZeroAndCannotCrit() {
        DamageHarness harness = damageHarness();
        MobInstance mob = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D);
        mob.currentHealth(1.2D);

        DamageResult result = harness.service.applyConditionDamage(
            null,
            AstEntity.mob(mob),
            5.0D,
            ConditionType.POISON
        );

        assertEquals(5.0D, result.finalDamage(), 0.0001D);
        assertEquals(0.0D, mob.currentHealth(), 0.0001D);
        assertEquals(false, result.critical());
    }

    private AstPlayer attacker() {
        AstPlayer attacker = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.FINAL_DAMAGE_MULTIPLIER, 100.0D
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
