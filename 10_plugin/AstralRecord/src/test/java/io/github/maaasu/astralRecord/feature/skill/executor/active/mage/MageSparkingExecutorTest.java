package io.github.maaasu.astralRecord.feature.skill.executor.active.mage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillEffectLineSegment;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillLineTargetHit;
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
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MageSparkingExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 23. メイジ スパーキングの実装契約
     * 検証契約: 雷弾は水平360度へ等間隔に生成され、50tickで半径5mまで2周する螺旋を描く。
     */
    @Test
    void createsConfiguredHorizontalSpiralProjectiles() {
        List<MageSparkingExecutor.SparkState> states = MageSparkingExecutor.spiralStates(
                new Location(null, 1.0D, 2.0D, 3.0D), 5, 0.0F
        );
        List<Vector> firstSteps = states.stream()
                .map(state -> state.advanceSpiral(0.10D, Math.toRadians(14.4D)))
                .toList();

        assertEquals(5, states.size());
        assertTrue(firstSteps.stream().allMatch(step -> Math.abs(step.getY()) < 1.0E-9D));
        assertEquals(Math.PI * 2.0D / 5.0D,
                states.getFirst().direction().angle(states.get(1).direction()), 1.0E-6D);

        Vector displacement = firstSteps.getFirst().clone();
        for (int tick = 1; tick < 50; tick++) {
            displacement.add(states.getFirst().advanceSpiral(0.10D, Math.toRadians(14.4D)));
        }
        assertEquals(5.0D, displacement.length(), 1.0E-6D);
        assertEquals(0.0D, displacement.angle(new Vector(0.0D, 0.0D, 1.0D)), 1.0E-6D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 23. メイジ スパーキングの実装契約 > ### 23.1 数値・弾道・対象
     * 検証契約: 線形移動epsilonを超える螺旋差分では、微小距離でも実際の差分方向へ進行方向を更新する。
     */
    @Test
    void updatesDirectionForMovementJustAboveLinearEpsilon() {
        MageSparkingExecutor.SparkState state = MageSparkingExecutor.spiralStates(
                new Location(null, 0.0D, 0.0D, 0.0D), 1, 0.0F
        ).getFirst();

        Vector movement = state.advanceSpiral(1.0E-6D, Math.PI / 2.0D);

        assertEquals(0.0D, state.direction().angle(movement), 1.0E-9D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 23. メイジ スパーキングの実装契約
     * 検証契約: Block面法線による鏡面反射で壁へ向かう成分だけを反転する。
     */
    @Test
    void reflectsDirectionAgainstBlockFaceNormal() {
        Vector reflected = MageSparkingExecutor.reflect(
                new Vector(1.0D, 0.0D, 1.0D), new Vector(-1.0D, 0.0D, 0.0D)
        );

        assertEquals(-Math.sqrt(0.5D), reflected.getX(), 1.0E-9D);
        assertEquals(0.0D, reflected.getY(), 1.0E-9D);
        assertEquals(Math.sqrt(0.5D), reflected.getZ(), 1.0E-9D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 23. メイジ スパーキングの実装契約
     * 検証契約: 同じ発動で命中済みの手前Mobへ再接触した雷弾は、奥のMobへ貫通せず消滅し、120%雷魔法と25%・100tick感電を1回だけ適用する。
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void hitsEachTargetOnlyOncePerCastWithShockChance() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                targeting, combat, effects, mock(SkillProjectileService.class),
                mock(SkillMovementService.class), mock(TemporarySkillEffectService.class), tasks
        );
        World world = mock(World.class);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        Location playerLocation = new Location(world, 0.0D, 64.0D, 0.0D);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(playerLocation);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        AstEntity target = mock(AstEntity.class);
        UUID targetId = UUID.randomUUID();
        when(target.id()).thenReturn(targetId);
        AstEntity rearTarget = mock(AstEntity.class);
        when(rearTarget.id()).thenReturn(UUID.randomUUID());
        SkillTargetingService.LineTargetSnapshot snapshot = mock(SkillTargetingService.LineTargetSnapshot.class);
        when(targeting.captureLineTargetSnapshot(player)).thenReturn(snapshot);
        when(targeting.lineTargetHits(
                same(player), same(snapshot), any(), any(), anyDouble(), eq(0.60D), anyInt(), eq(true)
        )).thenAnswer(invocation -> {
            Location origin = invocation.getArgument(2, Location.class);
            Vector direction = invocation.getArgument(3, Vector.class);
            return List.of(
                    new SkillLineTargetHit(
                            target, origin.clone().add(direction.clone().multiply(0.2D)), 0.2D
                    ),
                    new SkillLineTargetHit(
                            rearTarget, origin.clone().add(direction.clone().multiply(0.4D)), 0.4D
                    )
            );
        });

        MageSparkingExecutor executor = new MageSparkingExecutor(services);
        assertTrue(executor.cast(new SkillCastContext(
                definition(), new PlayerSkillCaster(astPlayer), null, List.of(), playerLocation,
                StatusSnapshot.empty(), SkillCastTrigger.PLAYER_COMMAND, Instant.EPOCH
        )).success());

        ArgumentCaptor<IntConsumer> tick = ArgumentCaptor.forClass(IntConsumer.class);
        verify(tasks).repeat(eq(playerId), any(), eq(0L), eq(1L), eq(50), tick.capture());
        tick.getValue().accept(0);

        ArgumentCaptor<ActiveSkillCondition[]> conditions = ArgumentCaptor.forClass(ActiveSkillCondition[].class);
        verify(combat, times(1)).hit(
                any(AstEntity.class), same(target), eq(AttackType.MAGIC), eq(DamageElement.LIGHTNING),
                eq(1.2D), conditions.capture()
        );
        assertEquals(1, conditions.getValue().length);
        assertEquals(ConditionType.SHOCKED, conditions.getValue()[0].type());
        assertEquals(25.0D, conditions.getValue()[0].chance());
        assertEquals(100L, conditions.getValue()[0].durationTicks());
        verify(combat, never()).hit(
                any(AstEntity.class), same(rearTarget), any(), any(), anyDouble(), any(ActiveSkillCondition[].class)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 23. メイジ スパーキングの実装契約 > ### 23.1 数値・弾道・対象
     * 検証契約: 壁へ衝突した雷弾は現在の進行方向と以後の螺旋座標軸を反射し、壁の反対側へ渦を継続する。
     */
    @Test
    void reflectsFollowingSpiralMovementAfterWallCollision() {
        MageSparkingExecutor.SparkState original = MageSparkingExecutor.spiralStates(
                new Location(null, 0.0D, 0.0D, 0.0D), 1, -90.0F
        ).getFirst();
        MageSparkingExecutor.SparkState reflected = MageSparkingExecutor.spiralStates(
                new Location(null, 0.0D, 0.0D, 0.0D), 1, -90.0F
        ).getFirst();
        double radiansPerTick = Math.toRadians(14.4D);
        original.advanceSpiral(0.10D, radiansPerTick);
        reflected.advanceSpiral(0.10D, radiansPerTick);
        reflected.reflectTrajectory(new Vector(-1.0D, 0.0D, 0.0D));

        Vector originalNext = original.advanceSpiral(0.10D, radiansPerTick);
        Vector reflectedNext = reflected.advanceSpiral(0.10D, radiansPerTick);

        assertEquals(-originalNext.getX(), reflectedNext.getX(), 1.0E-9D);
        assertEquals(originalNext.getZ(), reflectedNext.getZ(), 1.0E-9D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 23. メイジ スパーキングの実装契約 > ### 23.1 数値・弾道・対象
     * 検証契約: tick途中で壁へ衝突した雷弾は現在方向を反射し、0.05m離してから同tickの残距離を消費する。
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void continuesRemainingSpiralMovementAfterWallReflection() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                targeting, mock(SkillCombatService.class), effects, mock(SkillProjectileService.class),
                mock(SkillMovementService.class), mock(TemporarySkillEffectService.class), tasks
        );
        World world = mock(World.class);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        Location playerLocation = new Location(world, 0.0D, 64.0D, 0.0D);
        Location sparkStart = playerLocation.clone().add(0.0D, 0.75D, 0.0D);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(playerLocation);
        when(player.getYaw()).thenReturn(-90.0F);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        SkillTargetingService.LineTargetSnapshot snapshot = mock(SkillTargetingService.LineTargetSnapshot.class);
        when(targeting.captureLineTargetSnapshot(player)).thenReturn(snapshot);

        Vector initialMovement = MageSparkingExecutor.spiralStates(sparkStart, 1, -90.0F)
                .getFirst().advanceSpiral(0.65D, Math.toRadians(14.4D));
        Vector initialDirection = initialMovement.clone().normalize();
        Location impact = sparkStart.clone().add(initialDirection.clone().multiply(0.20D));
        Vector wallNormal = initialDirection.clone().multiply(-1.0D);
        SkillTargetingService.BlockHit wall = new SkillTargetingService.BlockHit(impact, wallNormal);
        when(targeting.blockHit(any(), any(), anyDouble()))
                .thenReturn(wall, (SkillTargetingService.BlockHit) null);
        when(targeting.lineTargetHits(
                same(player), same(snapshot), any(), any(), anyDouble(), eq(0.60D), eq(1), anyBoolean()
        )).thenReturn(List.of());

        MageSparkingExecutor executor = new MageSparkingExecutor(services);
        assertTrue(executor.cast(new SkillCastContext(
                definition(1, 0.65D), new PlayerSkillCaster(astPlayer), null, List.of(), playerLocation,
                StatusSnapshot.empty(), SkillCastTrigger.PLAYER_COMMAND, Instant.EPOCH
        )).success());

        ArgumentCaptor<IntConsumer> tick = ArgumentCaptor.forClass(IntConsumer.class);
        verify(tasks).repeat(eq(playerId), any(), eq(0L), eq(1L), eq(50), tick.capture());
        tick.getValue().accept(0);

        ArgumentCaptor<Vector> directions = ArgumentCaptor.forClass(Vector.class);
        ArgumentCaptor<Double> distances = ArgumentCaptor.forClass(Double.class);
        verify(targeting, times(2)).blockHit(any(), directions.capture(), distances.capture());
        assertEquals(-1.0D, directions.getAllValues().get(0).dot(directions.getAllValues().get(1)), 1.0E-9D);
        assertEquals(0.65D, distances.getAllValues().get(0), 1.0E-9D);
        assertEquals(0.40D, distances.getAllValues().get(1), 1.0E-9D);

        ArgumentCaptor<List<SkillEffectLineSegment>> segments = ArgumentCaptor.forClass(List.class);
        verify(effects).lines(any(), segments.capture(), eq(0.32D), any());
        assertEquals(2, segments.getValue().size());
        Vector reflectedDirection = initialDirection.clone().multiply(-1.0D);
        Location expectedSecondStart = impact.clone().add(reflectedDirection.clone().multiply(0.05D));
        Location expectedEnd = expectedSecondStart.clone().add(reflectedDirection.clone().multiply(0.40D));
        assertEquals(expectedSecondStart, segments.getValue().get(1).start());
        assertEquals(expectedEnd, segments.getValue().get(1).end());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 23. メイジ スパーキングの実装契約 > ### 23.1 数値・弾道・対象
     * 検証契約: Blockより手前のMobだけを命中候補にし、Blockと同距離または奥のMobへは命中しない。
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void hitsMobBeforeWallButNotAtOrBehindWall() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                targeting, combat, mock(SkillEffectService.class), mock(SkillProjectileService.class),
                mock(SkillMovementService.class), mock(TemporarySkillEffectService.class), tasks
        );
        World world = mock(World.class);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        Location playerLocation = new Location(world, 0.0D, 64.0D, 0.0D);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(playerLocation);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        AstEntity frontTarget = mock(AstEntity.class);
        when(frontTarget.id()).thenReturn(UUID.randomUUID());
        AstEntity blockedTarget = mock(AstEntity.class);
        when(blockedTarget.id()).thenReturn(UUID.randomUUID());
        SkillTargetingService.LineTargetSnapshot snapshot = mock(SkillTargetingService.LineTargetSnapshot.class);
        when(targeting.captureLineTargetSnapshot(player)).thenReturn(snapshot);
        when(targeting.blockHit(any(), any(), anyDouble())).thenAnswer(invocation -> {
            Location origin = invocation.getArgument(0, Location.class);
            Vector direction = invocation.getArgument(1, Vector.class);
            return new SkillTargetingService.BlockHit(
                    origin.clone().add(direction.clone().multiply(0.08D)),
                    direction.clone().multiply(-1.0D)
            );
        });
        ArgumentCaptor<Double> collisionRanges = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Boolean> includeEnds = ArgumentCaptor.forClass(Boolean.class);
        when(targeting.lineTargetHits(
                same(player), same(snapshot), any(), any(), collisionRanges.capture(),
                eq(0.60D), eq(1), includeEnds.capture()
        )).thenAnswer(invocation -> {
            if (collisionRanges.getAllValues().size() == 1) {
                Location origin = invocation.getArgument(2, Location.class);
                Vector direction = invocation.getArgument(3, Vector.class);
                return List.of(new SkillLineTargetHit(
                        frontTarget, origin.clone().add(direction.clone().multiply(0.04D)), 0.04D
                ));
            }
            return List.of();
        });

        MageSparkingExecutor executor = new MageSparkingExecutor(services);
        assertTrue(executor.cast(new SkillCastContext(
                definition(2), new PlayerSkillCaster(astPlayer), null, List.of(), playerLocation,
                StatusSnapshot.empty(), SkillCastTrigger.PLAYER_COMMAND, Instant.EPOCH
        )).success());

        ArgumentCaptor<IntConsumer> tick = ArgumentCaptor.forClass(IntConsumer.class);
        verify(tasks).repeat(eq(playerId), any(), eq(0L), eq(1L), eq(50), tick.capture());
        tick.getValue().accept(0);

        assertEquals(List.of(0.08D, 0.08D), collisionRanges.getAllValues());
        assertEquals(List.of(false, false), includeEnds.getAllValues());
        verify(combat, times(1)).hit(
                any(AstEntity.class), same(frontTarget), eq(AttackType.MAGIC), eq(DamageElement.LIGHTNING),
                eq(1.2D), any(ActiveSkillCondition[].class)
        );
        verify(combat, never()).hit(
                any(AstEntity.class), same(blockedTarget), any(), any(), anyDouble(), any(ActiveSkillCondition[].class)
        );
    }

    private static SkillDefinition definition() {
        return definition(5);
    }

    private static SkillDefinition definition(int projectileCount) {
        return definition(projectileCount, 0.10D);
    }

    private static SkillDefinition definition(int projectileCount, double spiralRadiusGrowth) {
        return new SkillDefinition(
                MageSparkingExecutor.ID,
                MageSparkingExecutor.ID,
                "スパーキング",
                null,
                "LIGHTNING_ROD",
                List.of(),
                160L,
                0.0D,
                4L,
                1,
                null,
                Map.of(
                        "damageRatio", 1.2D,
                        "projectileCount", projectileCount,
                        "spiralRadiusGrowth", spiralRadiusGrowth,
                        "spiralDegreesPerTick", 14.4D,
                        "projectileHitRadius", 0.60D,
                        "durationTicks", 50,
                        "shockChance", 25.0D,
                        "shockDurationTicks", 100
                ),
                List.of("active", "magic", "lightning"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                14.0D
        );
    }

}
