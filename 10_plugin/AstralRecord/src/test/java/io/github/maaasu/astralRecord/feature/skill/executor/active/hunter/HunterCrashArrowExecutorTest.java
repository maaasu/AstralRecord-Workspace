package io.github.maaasu.astralRecord.feature.skill.executor.active.hunter;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillCombatService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillMovementService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillProjectileService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTaskService;
import io.github.maaasu.astralRecord.feature.skill.active.service.TemporarySkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HunterCrashArrowExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 18. ハンタークラッシュアローの実装契約 > ### 18.1 数値・対象・ダメージ
     * 検証契約: クラッシュアローは重力なし・非貫通・1体命中の線形飛翔体で、水色の軌跡と着弾粒子を使う。
     */
    @Test
    void usesSpecifiedLinearCrashArrowProjectile() {
        SkillProjectileSpec projectile = HunterCrashArrowExecutor.crashArrowProjectile(14.0D, 1.35D, 0.45D);

        assertEquals(14.0D, projectile.range());
        assertEquals(1.35D, projectile.speed());
        assertEquals(0.45D, projectile.hitRadius());
        assertFalse(projectile.piercing());
        assertEquals(1, projectile.maxHits());
        assertEquals(SharedParticleDefinitions.SKILL_HUNTER_CRASH_ARROW_TRAIL, projectile.trail());
        assertEquals(SharedParticleDefinitions.SKILL_HUNTER_CRASH_ARROW_IMPACT, projectile.impact());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 18. ハンタークラッシュアローの実装契約 > ### 18.1 数値・対象・ダメージ
     * 検証契約: 最初のMob 1体へRANGED/NONEの低い倍率と、レベル解決済みの3倍シールドブレイク倍率を同じhitへ渡し、詠唱後の射撃音を1回表示する。
     */
    @Test
    void launchesArrowAndAppliesShieldBreakMultiplier() {
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillProjectileService projectiles = mock(SkillProjectileService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                mock(SkillTargetingService.class), combat, effects, projectiles,
                mock(SkillMovementService.class), mock(TemporarySkillEffectService.class), mock(SkillTaskService.class)
        );
        Player player = mock(Player.class);
        Location eyeLocation = new Location(null, 2.0D, 64.0D, 3.0D);
        when(player.getEyeLocation()).thenReturn(eyeLocation);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        HunterCrashArrowExecutor executor = new HunterCrashArrowExecutor(services);

        SkillCastResult result = executor.cast(new SkillCastContext(
                definition(), new PlayerSkillCaster(astPlayer), null, List.of(), eyeLocation,
                StatusSnapshot.empty(), SkillCastTrigger.PLAYER_COMMAND, Instant.EPOCH
        ));

        ArgumentCaptor<SkillProjectileSpec> projectileCaptor = ArgumentCaptor.forClass(SkillProjectileSpec.class);
        ArgumentCaptor<BiConsumer<AstEntity, Location>> hitCaptor = biConsumerCaptor();
        verify(projectiles).launch(
                same(player), any(Location.class), any(Vector.class), projectileCaptor.capture(), hitCaptor.capture(), any()
        );
        assertEquals(HunterCrashArrowExecutor.crashArrowProjectile(14.0D, 1.35D, 0.45D), projectileCaptor.getValue());
        assertTrue(result.success());
        verify(effects, times(1)).sound(any(Location.class), eq(Sound.ENTITY_ARROW_SHOOT), eq(1.15F), eq(0.75F));

        AstEntity target = mock(AstEntity.class);
        hitCaptor.getValue().accept(target, eyeLocation);

        ArgumentCaptor<AstEntity> attackerCaptor = ArgumentCaptor.forClass(AstEntity.class);
        verify(combat).hit(
                attackerCaptor.capture(), same(target), eq(AttackType.RANGED), eq(DamageElement.NONE), eq(0.45D), eq(3.0D)
        );
        assertSame(astPlayer, attackerCaptor.getValue().player());
    }

    private static SkillDefinition definition() {
        return new SkillDefinition(
                HunterCrashArrowExecutor.ID,
                HunterCrashArrowExecutor.ID,
                "クラッシュアロー",
                null,
                "SPECTRAL_ARROW",
                List.of(),
                120L,
                14.0D,
                20L,
                1,
                null,
                Map.of(
                        "range", 14.0D,
                        "damageRatio", 0.45D,
                        "shieldBreakMultiplier", 3.0D,
                        "projectileSpeed", 1.35D,
                        "projectileHitRadius", 0.45D
                ),
                List.of("active", "ranged"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.ENERGY,
                14.0D
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<BiConsumer<AstEntity, Location>> biConsumerCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(BiConsumer.class);
    }
}
