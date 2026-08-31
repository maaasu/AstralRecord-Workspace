package io.github.maaasu.astralRecord.feature.skill.executor.active.mage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileTermination;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MageFrostBallExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 26. メイジ フロストボールの実装契約 > ### 26.1 数値・対象・終端
     * 検証契約: フロストボールは水色の球体粒子を使う、重力なし・非貫通・1回衝突の仮想飛翔体である。
     */
    @Test
    void usesSpecifiedSphericalFrostBallProjectile() {
        SkillProjectileSpec projectile = MageFrostBallExecutor.frostBallProjectile(16.0D, 1.45D, 0.45D);

        assertEquals(16.0D, projectile.range());
        assertEquals(1.45D, projectile.speed());
        assertEquals(0.45D, projectile.hitRadius());
        assertFalse(projectile.piercing());
        assertEquals(1, projectile.maxHits());
        assertEquals(SharedParticleDefinitions.MAGE_FROST_BALL_TRAIL, projectile.trail());
        assertNull(projectile.impact());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 26. メイジ フロストボールの実装契約 > ### 26.2 演出・DPS・入手・テスト契約
     * 検証契約: 発射音はonCast.soundへ一元化し、executorから重複再生しない。
     */
    @Test
    void delegatesLaunchSoundToOnCastConfiguration() {
        Fixture fixture = fixture();
        assertTrue(fixture.executor.cast(fixture.context).success());

        verify(fixture.effects, never()).sound(
                any(Location.class), eq(Sound.ENTITY_SNOW_GOLEM_SHOOT), anyFloat(), anyFloat()
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 26. メイジ フロストボールの実装契約 > ### 26.1 数値・対象・終端
     * 検証契約: 敵またはBlockへ着弾すると、氷晶の範囲内から最大4体へMAGIC/ICEの一撃を与える。
     */
    @Test
    void detonatesOnEntityHitAndDamagesMultipleTargets() {
        Fixture fixture = fixture();
        SkillCastResult result = fixture.executor.cast(fixture.context);

        ArgumentCaptor<BiConsumer<AstEntity, Location>> hitCaptor = biConsumerCaptor();
        verify(fixture.projectiles).launchWithTermination(
                same(fixture.player), any(Location.class), any(), any(SkillProjectileSpec.class), hitCaptor.capture(), any()
        );
        AstEntity impactedTarget = mock(AstEntity.class);
        AstEntity nearbyTarget = mock(AstEntity.class);
        Location impact = new Location(null, 5.0D, 64.0D, 0.0D);
        when(fixture.targeting.inRadius(same(fixture.player), same(impact), eq(2.25D), eq(2.25D), eq(4), eq(true)))
                .thenReturn(List.of(impactedTarget, nearbyTarget));

        hitCaptor.getValue().accept(impactedTarget, impact);

        assertTrue(result.success());
        verify(fixture.effects).point(same(impact), eq(SharedParticleDefinitions.MAGE_FROST_BALL_IMPACT));
        verify(fixture.effects).point(same(impact), eq(SharedParticleDefinitions.SKILL_MAGE_ICE));
        verify(fixture.effects).sound(same(impact), eq(Sound.BLOCK_GLASS_BREAK), eq(0.65F), eq(1.45F));
        verify(fixture.combat).hit(any(AstEntity.class), same(impactedTarget), eq(AttackType.MAGIC), eq(DamageElement.ICE), eq(1.32D));
        verify(fixture.combat).hit(any(AstEntity.class), same(nearbyTarget), eq(AttackType.MAGIC), eq(DamageElement.ICE), eq(1.32D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 26. メイジ フロストボールの実装契約 > ### 26.1 数値・対象・終端
     * 検証契約: Block終端だけで氷晶が炸裂し、射程終端ではダメージも炸裂も発生しない。
     */
    @Test
    void detonatesOnBlockButNotRangeTermination() {
        Fixture fixture = fixture();
        fixture.executor.cast(fixture.context);

        ArgumentCaptor<Consumer<SkillProjectileTermination>> terminationCaptor = consumerCaptor();
        verify(fixture.projectiles).launchWithTermination(
                same(fixture.player), any(Location.class), any(), any(SkillProjectileSpec.class), any(), terminationCaptor.capture()
        );
        Location range = new Location(null, 16.0D, 64.0D, 0.0D);
        terminationCaptor.getValue().accept(new SkillProjectileTermination(SkillProjectileTermination.Type.RANGE, range, range));
        verify(fixture.effects, never()).sound(any(Location.class), eq(Sound.BLOCK_GLASS_BREAK), anyFloat(), anyFloat());
        verify(fixture.combat, never()).hit(any(), any(), any(), any(), anyDouble());

        Location blockImpact = new Location(null, 8.0D, 64.0D, 0.0D);
        Location effectCenter = new Location(null, 7.9D, 64.0D, 0.0D);
        AstEntity blockTarget = mock(AstEntity.class);
        when(fixture.targeting.inRadius(same(fixture.player), same(effectCenter), eq(2.25D), eq(2.25D), eq(4), eq(true)))
                .thenReturn(List.of(blockTarget));
        terminationCaptor.getValue().accept(new SkillProjectileTermination(
                SkillProjectileTermination.Type.BLOCK, blockImpact, effectCenter
        ));

        verify(fixture.effects).point(same(blockImpact), eq(SharedParticleDefinitions.MAGE_FROST_BALL_IMPACT));
        verify(fixture.effects, times(1)).sound(same(blockImpact), eq(Sound.BLOCK_GLASS_BREAK), eq(0.65F), eq(1.45F));
        verify(fixture.combat).hit(any(AstEntity.class), same(blockTarget), eq(AttackType.MAGIC), eq(DamageElement.ICE), eq(1.32D));
    }

    private static Fixture fixture() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillProjectileService projectiles = mock(SkillProjectileService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                targeting,
                combat,
                effects,
                projectiles,
                mock(SkillMovementService.class),
                mock(TemporarySkillEffectService.class),
                mock(SkillTaskService.class)
        );
        Player player = mock(Player.class);
        Location eyeLocation = new Location(null, 2.0D, 64.0D, 3.0D);
        when(player.getEyeLocation()).thenReturn(eyeLocation);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        SkillCastContext context = new SkillCastContext(
                definition(validParams()),
                new PlayerSkillCaster(astPlayer),
                null,
                List.of(),
                eyeLocation,
                StatusSnapshot.empty(),
                SkillCastTrigger.PLAYER_COMMAND,
                Instant.EPOCH
        );
        return new Fixture(new MageFrostBallExecutor(services), context, player, targeting, combat, effects, projectiles);
    }

    private static SkillDefinition definition(Map<String, Object> params) {
        return new SkillDefinition(
                MageFrostBallExecutor.ID,
                MageFrostBallExecutor.ID,
                "フロストボール",
                null,
                "SNOWBALL",
                List.of(),
                80L,
                4.0D,
                4L,
                1,
                null,
                params,
                List.of("active", "magic"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                12.0D,
                null,
                5
        );
    }

    private static Map<String, Object> validParams() {
        return Map.of(
                "range", 16.0D,
                "radius", 2.25D,
                "damageRatio", 1.32D,
                "maxTargets", 4,
                "projectileSpeed", 1.45D,
                "projectileHitRadius", 0.45D
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<BiConsumer<AstEntity, Location>> biConsumerCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(BiConsumer.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Consumer<SkillProjectileTermination>> consumerCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Consumer.class);
    }

    private record Fixture(
            MageFrostBallExecutor executor,
            SkillCastContext context,
            Player player,
            SkillTargetingService targeting,
            SkillCombatService combat,
            SkillEffectService effects,
            SkillProjectileService projectiles
    ) {
    }
}
