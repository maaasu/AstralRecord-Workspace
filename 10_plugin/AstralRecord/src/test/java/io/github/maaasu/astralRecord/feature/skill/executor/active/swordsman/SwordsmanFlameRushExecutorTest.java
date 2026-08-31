package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillCombatService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillMovementService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillProjectileService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTaskService;
import io.github.maaasu.astralRecord.feature.skill.active.service.TemporarySkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillLevelDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.service.LearnedSkillResolver;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SwordsmanFlameRushExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 11. フレイムラッシュの実装契約
     * 検証契約: 初撃は火属性78%だけを即時適用し、近距離の横薙ぎ粒子を右から左へ6 frameで流す。ノックバックを使わず4 tick後の縦斬りで90%を適用する。
     */
    @Test
    void firstHitHasNoKnockbackAndSecondHitFollowsAfterFourTicks() {
        Fixture fixture = fixture(7);
        ArgumentCaptor<Runnable> verticalCaptor = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<IntConsumer> horizontalCaptor = ArgumentCaptor.forClass(IntConsumer.class);

        assertTrue(fixture.executor.cast(fixture.context).success());

        verify(fixture.combat).hit(
                any(AstEntity.class), same(fixture.target), eq(AttackType.MELEE), eq(DamageElement.FIRE), eq(0.78D)
        );
        verify(fixture.combat, never()).knockback(any(AstEntity.class), any(Location.class), anyDouble(), anyDouble());
        verify(fixture.tasks).later(
                eq(fixture.player.getUniqueId()),
                eq(SwordsmanFlameRushExecutor.ID + ":vertical"),
                eq(4L),
                verticalCaptor.capture()
        );
        verify(fixture.tasks).repeat(
                eq(fixture.player.getUniqueId()),
                eq(SwordsmanFlameRushExecutor.ID + ":horizontal"),
                eq(0L),
                eq(1L),
                eq(6),
                horizontalCaptor.capture()
        );

        clearInvocations(fixture.effects);
        IntConsumer horizontalFrames = horizontalCaptor.getValue();
        horizontalFrames.accept(0);
        horizontalFrames.accept(5);

        ArgumentCaptor<Double> horizontalStartCaptor = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> horizontalEndCaptor = ArgumentCaptor.forClass(Double.class);
        verify(fixture.effects, times(6)).viewArcSegment(
                any(), any(), anyDouble(), horizontalStartCaptor.capture(), horizontalEndCaptor.capture(), eq(8),
                eq(SharedParticleDefinitions.SWORDSMAN_FLAME_RUSH_HORIZONTAL_DUST)
        );
        verify(fixture.effects, times(6)).viewArcSegment(
                any(), any(), anyDouble(), anyDouble(), anyDouble(), eq(6),
                eq(SharedParticleDefinitions.SWORDSMAN_FLAME_RUSH_HORIZONTAL_FLAME)
        );
        assertEquals(55.0D, horizontalStartCaptor.getAllValues().get(0));
        assertEquals(36.66666666666667D, horizontalEndCaptor.getAllValues().get(0));
        assertEquals(-36.66666666666667D, horizontalStartCaptor.getAllValues().get(3));
        assertEquals(-55.0D, horizontalEndCaptor.getAllValues().get(3));

        verticalCaptor.getValue().run();

        verify(fixture.combat).hit(
                any(AstEntity.class), same(fixture.target), eq(AttackType.MELEE), eq(DamageElement.FIRE), eq(0.90D)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 11. フレイムラッシュの実装契約
     * 検証契約: 解決済みのLv.8/9/10の2撃目だけが、5秒・35%/40%/45%の炎上判定を伴う。
     */
    @Test
    void secondHitUsesResolvedBurningChance() {
        assertSecondHitBurningChance(8, 35.0D);
        assertSecondHitBurningChance(9, 40.0D);
        assertSecondHitBurningChance(10, 45.0D);
    }

    private static void assertSecondHitBurningChance(int skillLevel, double expectedChance) {
        Fixture fixture = fixture(skillLevel, resolvedBurningChance(skillLevel));
        ArgumentCaptor<Runnable> verticalCaptor = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<ActiveSkillCondition> conditionCaptor = ArgumentCaptor.forClass(ActiveSkillCondition.class);

        assertTrue(fixture.executor.cast(fixture.context).success());
        verify(fixture.tasks).later(any(), any(), anyLong(), verticalCaptor.capture());

        verticalCaptor.getValue().run();

        verify(fixture.combat).hit(
                any(AstEntity.class),
                same(fixture.target),
                eq(AttackType.MELEE),
                eq(DamageElement.FIRE),
                eq(0.90D),
                conditionCaptor.capture()
        );
        ActiveSkillCondition burning = conditionCaptor.getValue();
        assertEquals(ConditionType.BURNING, burning.type());
        assertEquals(expectedChance, burning.chance());
        assertEquals(100L, burning.durationTicks());
    }

    private static Fixture fixture(int skillLevel) {
        return fixture(skillLevel, skillLevel >= 8 ? 35.0D : 0.0D);
    }

    private static Fixture fixture(int skillLevel, double burningChance) {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                targeting,
                combat,
                effects,
                mock(SkillProjectileService.class),
                mock(SkillMovementService.class),
                mock(TemporarySkillEffectService.class),
                tasks
        );
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);
        when(player.getEyeLocation()).thenReturn(new Location(world, 0.0D, 65.6D, 0.0D, 0.0F, 0.0F));
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        AstEntity target = mock(AstEntity.class);
        when(target.location()).thenReturn(new Location(world, 0.0D, 64.0D, 4.0D));
        when(targeting.inCone(same(player), eq(6.0D), eq(60.0D), eq(5), eq(true))).thenReturn(List.of(target));
        when(combat.hit(any(AstEntity.class), same(target), eq(AttackType.MELEE), eq(DamageElement.FIRE), eq(0.78D)))
                .thenReturn(new DamageResult(20.0D));
        when(combat.hit(any(AstEntity.class), same(target), eq(AttackType.MELEE), eq(DamageElement.FIRE), eq(0.90D)))
                .thenReturn(new DamageResult(20.0D));
        when(combat.hit(
                any(AstEntity.class),
                same(target),
                eq(AttackType.MELEE),
                eq(DamageElement.FIRE),
                eq(0.90D),
                any(ActiveSkillCondition.class)
        )).thenReturn(new DamageResult(20.0D));
        StatusSnapshot snapshot = mock(StatusSnapshot.class);
        LearnedSkillInstance learned = new LearnedSkillInstance(
                UUID.randomUUID(), UUID.randomUUID(), SwordsmanFlameRushExecutor.ID, skillLevel, List.of(), 0, null, null
        );
        SkillCastContext context = new SkillCastContext(
                definition(burningChance),
                new PlayerSkillCaster(astPlayer),
                null,
                List.of(),
                player.getEyeLocation(),
                snapshot,
                SkillCastTrigger.PLAYER_COMMAND,
                Instant.EPOCH,
                learned
        );
        return new Fixture(player, target, combat, effects, tasks, new SwordsmanFlameRushExecutor(services), context);
    }

    private static SkillDefinition definition(double burningChance) {
        return new SkillDefinition(
                SwordsmanFlameRushExecutor.ID,
                SwordsmanFlameRushExecutor.ID,
                "フレイムラッシュ",
                null,
                "CRIMSON_ROOTS",
                List.of(),
                80L,
                0.0D,
                0L,
                1,
                null,
                Map.of(
                        "range", 6.0D,
                        "targetAngle", 60.0D,
                        "maxTargets", 5,
                        "damageRatios", List.of(0.78D, 0.90D),
                        "secondHitDelayTicks", 4,
                        "burningChance", burningChance,
                        "burningDurationTicks", 100L
                ),
                List.of("active", "melee", "fire"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.ENERGY,
                14.0D,
                null,
                10
        );
    }

    private static double resolvedBurningChance(int skillLevel) {
        LearnedSkillInstance learned = new LearnedSkillInstance(
                UUID.randomUUID(), UUID.randomUUID(), SwordsmanFlameRushExecutor.ID, skillLevel, List.of(), 0, null, null
        );
        SkillDefinition resolved = new LearnedSkillResolver(new ItemService())
                .resolve(definitionWithLevels(), learned)
                .definition();
        return new io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader(
                resolved.getId(), resolved.getParams()
        ).getDouble("burningChance", -1.0D);
    }

    private static SkillDefinition definitionWithLevels() {
        return new SkillDefinition(
                SwordsmanFlameRushExecutor.ID,
                SwordsmanFlameRushExecutor.ID,
                "フレイムラッシュ",
                null,
                "CRIMSON_ROOTS",
                List.of(),
                80L,
                0.0D,
                0L,
                1,
                null,
                Map.of(
                        "range", 6.0D,
                        "targetAngle", 60.0D,
                        "maxTargets", 5,
                        "damageRatios", List.of(0.78D, 0.90D),
                        "secondHitDelayTicks", 4,
                        "burningChance", 0.0D,
                        "burningDurationTicks", 100L
                ),
                List.of("active", "melee", "fire"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.ENERGY,
                14.0D,
                null,
                10,
                List.of(
                        new SkillLevelDefinition(8, 0L, 0.0D, 0L, Map.of("burningChance", 35.0D), List.of()),
                        new SkillLevelDefinition(9, 0L, 0.0D, 0L, Map.of("burningChance", 5.0D), List.of()),
                        new SkillLevelDefinition(10, 0L, 0.0D, 0L, Map.of("burningChance", 5.0D), List.of())
                ),
                List.of(),
                List.of()
        );
    }

    private record Fixture(
            Player player,
            AstEntity target,
            SkillCombatService combat,
            SkillEffectService effects,
            SkillTaskService tasks,
            SwordsmanFlameRushExecutor executor,
            SkillCastContext context
    ) {
    }
}
