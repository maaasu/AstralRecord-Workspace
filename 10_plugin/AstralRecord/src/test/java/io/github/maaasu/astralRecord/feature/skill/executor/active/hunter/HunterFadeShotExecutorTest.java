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
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HunterFadeShotExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 16. ハンターフェイドショットの実装契約 > ### 16.1 散弾・移動・演出
     * 検証契約: 5本の散弾方向は視線中央を含み、全角30度の両端へ左右対称かつ等間隔に並ぶ。
     */
    @Test
    void createsSymmetricalFivePelletSpread() {
        Vector forward = new Vector(0.0D, 0.0D, 1.0D);

        List<Vector> directions = HunterFadeShotExecutor.pelletDirections(forward, 5, 30.0D);

        assertEquals(5, directions.size());
        assertEquals(forward, directions.get(2));
        assertEquals(Math.cos(Math.toRadians(15.0D)), forward.dot(directions.getFirst()), 1.0E-9D);
        assertEquals(Math.cos(Math.toRadians(7.5D)), forward.dot(directions.get(1)), 1.0E-9D);
        assertEquals(Math.cos(Math.toRadians(7.5D)), forward.dot(directions.get(3)), 1.0E-9D);
        assertEquals(Math.cos(Math.toRadians(15.0D)), forward.dot(directions.getLast()), 1.0E-9D);
        assertEquals(-directions.getFirst().getX(), directions.getLast().getX(), 1.0E-9D);
        assertEquals(-directions.get(1).getX(), directions.get(3).getX(), 1.0E-9D);
        directions.forEach(direction -> assertEquals(1.0D, direction.length(), 1.0E-9D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 16. ハンターフェイドショットの実装契約 > ### 16.1 散弾・移動・演出
     * 検証契約: 発動時は5本を個別の非貫通飛翔体として放ち、同じMobへの各命中へRANGED/NONEの32%を適用した後、後方velocityを1回要求する。
     */
    @Test
    void launchesFiveIndependentPelletsThenBacksteps() {
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillProjectileService projectiles = mock(SkillProjectileService.class);
        SkillMovementService movement = mock(SkillMovementService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                mock(SkillTargetingService.class), combat, effects, projectiles,
                movement, mock(TemporarySkillEffectService.class), mock(SkillTaskService.class)
        );
        Player player = mock(Player.class);
        Location eyeLocation = new Location(null, 2.0D, 65.6D, 3.0D, 0.0F, 0.0F);
        when(player.getEyeLocation()).thenReturn(eyeLocation);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(movement.backstepVelocity(same(player), any(AstEntity.class), eq(0.35D)))
                .thenReturn(new Vector(0.0D, 0.0D, -0.35D));
        HunterFadeShotExecutor executor = new HunterFadeShotExecutor(services);

        SkillCastResult result = executor.cast(new SkillCastContext(
                fadeShotDefinition(), new PlayerSkillCaster(astPlayer), null, List.of(), eyeLocation,
                StatusSnapshot.empty(), SkillCastTrigger.PLAYER_COMMAND, Instant.EPOCH
        ));

        ArgumentCaptor<Vector> directionCaptor = ArgumentCaptor.forClass(Vector.class);
        ArgumentCaptor<SkillProjectileSpec> specCaptor = ArgumentCaptor.forClass(SkillProjectileSpec.class);
        ArgumentCaptor<BiConsumer<AstEntity, Location>> hitCaptor = biConsumerCaptor();
        InOrder executionOrder = inOrder(projectiles, movement);
        executionOrder.verify(projectiles, times(5)).launch(
                same(player), any(Location.class), directionCaptor.capture(), specCaptor.capture(), hitCaptor.capture(), any()
        );
        executionOrder.verify(movement).backstepVelocity(same(player), any(AstEntity.class), eq(0.35D));
        assertEquals(
                HunterFadeShotExecutor.pelletDirections(new Vector(0.0D, 0.0D, 1.0D), 5, 30.0D),
                directionCaptor.getAllValues()
        );
        specCaptor.getAllValues().forEach(spec -> {
            assertEquals(9.0D, spec.range());
            assertEquals(1.8D, spec.speed());
            assertEquals(0.30D, spec.hitRadius());
            assertFalse(spec.piercing());
            assertEquals(1, spec.maxHits());
        });

        AstEntity target = mock(AstEntity.class);
        hitCaptor.getAllValues().forEach(hit -> hit.accept(target, eyeLocation));
        verify(combat, times(5)).hit(
                any(AstEntity.class), same(target), eq(AttackType.RANGED), eq(DamageElement.NONE), eq(0.32D)
        );
        verify(effects).line(
                any(Location.class), any(Location.class), eq(0.35D), eq(SharedParticleDefinitions.HUNTER_FADE_SHOT_STEP)
        );
        verify(effects).sound(any(Location.class), eq(Sound.ENTITY_ARROW_SHOOT), eq(1.0F), eq(0.85F));
        assertTrue(result.success());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 16. ハンターフェイドショットの実装契約 > ### 16.1 散弾・移動・演出
     * 検証契約: 移動禁止状態でも5本の射撃は成功し、後方velocityと後退演出だけを適用しない。
     */
    @Test
    void succeedsWithoutMovementTrailWhenBackstepIsBlocked() {
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillProjectileService projectiles = mock(SkillProjectileService.class);
        SkillMovementService movement = mock(SkillMovementService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                mock(SkillTargetingService.class), mock(SkillCombatService.class), effects, projectiles,
                movement, mock(TemporarySkillEffectService.class), mock(SkillTaskService.class)
        );
        Player player = mock(Player.class);
        Location eyeLocation = new Location(null, 2.0D, 65.6D, 3.0D, 0.0F, 0.0F);
        when(player.getEyeLocation()).thenReturn(eyeLocation);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(movement.backstepVelocity(same(player), any(AstEntity.class), eq(0.35D)))
                .thenReturn(null);
        HunterFadeShotExecutor executor = new HunterFadeShotExecutor(services);

        SkillCastResult result = executor.cast(new SkillCastContext(
                fadeShotDefinition(), new PlayerSkillCaster(astPlayer), null, List.of(), eyeLocation,
                StatusSnapshot.empty(), SkillCastTrigger.PLAYER_COMMAND, Instant.EPOCH
        ));

        verify(projectiles, times(5)).launch(
                same(player), any(Location.class), any(Vector.class), any(SkillProjectileSpec.class), any(), any()
        );
        verify(movement).backstepVelocity(same(player), any(AstEntity.class), eq(0.35D));
        verify(effects, never()).line(any(), any(), anyDouble(), any());
        assertTrue(result.success());
    }

    private static SkillDefinition fadeShotDefinition() {
        return new SkillDefinition(
                HunterFadeShotExecutor.ID,
                HunterFadeShotExecutor.ID,
                "フェイドショット",
                null,
                "CROSSBOW",
                List.of(),
                80L,
                0.0D,
                0L,
                1,
                null,
                Map.of(
                        "range", 9.0D,
                        "damageRatio", 0.32D,
                        "pelletCount", 5,
                        "spreadAngle", 30.0D,
                        "projectileSpeed", 1.8D,
                        "projectileHitRadius", 0.30D,
                        "backstepVelocity", 0.35D
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
