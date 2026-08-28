package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillBallisticProjectileLaunch;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillBallisticProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillEffectLineSegment;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillLineTargetHit;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileTermination;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillProjectileServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック
     * 検証契約: 最初のMob衝突はENTITY終端として通知し、衝突地点だけを範囲攻撃の起点にできる。
     */
    @Test
    void terminatesAsEntityAtFirstMobCollision() {
        Fixture fixture = fixture();
        AstEntity target = mock(AstEntity.class);
        Location impact = new Location(null, 0.8D, 1.0D, 0.0D);
        when(target.id()).thenReturn(UUID.randomUUID());
        when(target.location()).thenReturn(new Location(null, 0.8D, 0.0D, 0.0D));
        when(fixture.targeting.inLine(any(), any(), any(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of(target));

        AtomicReference<AstEntity> hitTarget = new AtomicReference<>();
        AtomicReference<SkillProjectileTermination> termination = new AtomicReference<>();
        fixture.service.launchWithTermination(
                fixture.player, fixture.origin, new Vector(1.0D, 0.0D, 0.0D), spec(),
                (hit, ignored) -> hitTarget.set(hit), termination::set
        );

        assertSame(target, hitTarget.get());
        assertEquals(SkillProjectileTermination.Type.ENTITY, termination.get().type());
        assertEquals(impact, termination.get().location());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック
     * 検証契約: 地形Block衝突はBLOCK終端と正確なBlock衝突地点を通知する。
     */
    @Test
    void terminatesAsBlockAtTerrainCollision() {
        Fixture fixture = fixture();
        Location blockImpact = new Location(null, 0.9D, 0.0D, 0.0D);
        when(fixture.targeting.blockImpact(any(), any(), anyDouble())).thenReturn(blockImpact);
        when(fixture.targeting.clippedEnd(any(), any(), anyDouble()))
                .thenReturn(new Location(null, 0.8D, 0.0D, 0.0D));

        AtomicReference<SkillProjectileTermination> termination = new AtomicReference<>();
        fixture.service.launchWithTermination(
                fixture.player, fixture.origin, new Vector(1.0D, 0.0D, 0.0D), spec(),
                (target, ignored) -> { }, termination::set
        );

        assertEquals(SkillProjectileTermination.Type.BLOCK, termination.get().type());
        assertEquals(blockImpact, termination.get().location());
        assertEquals(new Location(null, 0.8D, 0.0D, 0.0D), termination.get().effectLocation());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック
     * 検証契約: Block面より手前のMobは短縮した表示終点にかかわらずENTITY終端とし、Block面と同距離ならBLOCKを優先する。
     */
    @Test
    void terminatesAsEntityForMobBeforeBlockImpactPlane() {
        Fixture fixture = fixture();
        Location blockImpact = new Location(null, 0.9D, 0.0D, 0.0D);
        Location safeEnd = new Location(null, 0.8D, 0.0D, 0.0D);
        AstEntity target = mock(AstEntity.class);
        when(target.id()).thenReturn(UUID.randomUUID());
        when(target.location()).thenReturn(new Location(null, 0.85D, 0.0D, 0.0D));
        when(fixture.targeting.blockImpact(any(), any(), anyDouble())).thenReturn(blockImpact);
        when(fixture.targeting.clippedEnd(any(), any(), anyDouble())).thenReturn(safeEnd);
        when(fixture.targeting.inLineBeforeBlock(any(), any(), any(), anyDouble(), anyDouble(), anyInt()))
                .thenReturn(List.of(target));

        AtomicReference<SkillProjectileTermination> termination = new AtomicReference<>();
        fixture.service.launchWithTermination(
                fixture.player, fixture.origin, new Vector(1.0D, 0.0D, 0.0D), spec(),
                (hit, ignored) -> { }, termination::set
        );

        assertEquals(SkillProjectileTermination.Type.ENTITY, termination.get().type());
        verify(fixture.targeting).inLineBeforeBlock(
                fixture.player, fixture.origin, new Vector(1.0D, 0.0D, 0.0D), 0.9D, 0.45D, 1
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック
     * 検証契約: 最大射程まで無衝突ならRANGE終端となり、着弾爆発を起こす契機と区別できる。
     */
    @Test
    void terminatesAsRangeWithoutCollision() {
        Fixture fixture = fixture();
        Location end = new Location(null, 1.0D, 0.0D, 0.0D);
        when(fixture.targeting.clippedEnd(any(), any(), anyDouble())).thenReturn(end);

        AtomicReference<SkillProjectileTermination> termination = new AtomicReference<>();
        fixture.service.launchWithTermination(
                fixture.player, fixture.origin, new Vector(1.0D, 0.0D, 0.0D), spec(),
                (target, ignored) -> { }, termination::set
        );

        assertEquals(SkillProjectileTermination.Type.RANGE, termination.get().type());
        assertEquals(end, termination.get().location());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 17. ハンター アローレインの実装契約 > ### 17.2 弾道
     * 検証契約: Lv.5相当81本を3本ずつ毎tick開始し、寿命14tickの重複中も同一tickのMob候補snapshotを共有して全弾を終了・task cancelする。
    */
    @Test
    @SuppressWarnings("unchecked")
    void ballisticVolleyEmitsConfiguredBatchSizeWithinOneTask() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        Player player = mock(Player.class);
        World world = mock(World.class);
        UUID playerId = UUID.randomUUID();
        SkillTargetingService.LineTargetSnapshot targetSnapshot =
                mock(SkillTargetingService.LineTargetSnapshot.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(new Location(world, 0.0D, 0.0D, 0.0D));
        when(targeting.clippedEnd(any(), any(), anyDouble())).thenAnswer(invocation -> {
            Location origin = invocation.getArgument(0, Location.class);
            Vector direction = invocation.getArgument(1, Vector.class);
            double range = invocation.getArgument(2, Double.class);
            return origin.clone().add(direction.clone().multiply(range));
        });
        when(targeting.inLine(any(), any(), any(), anyDouble(), anyDouble(), anyInt())).thenReturn(List.of());
        when(targeting.captureLineTargetSnapshot(player)).thenReturn(targetSnapshot);
        AtomicBoolean cancelled = new AtomicBoolean();
        doAnswer(invocation -> {
            cancelled.set(true);
            return null;
        }).when(tasks).cancel(eq(playerId), anyString());
        doAnswer(invocation -> {
            int executions = invocation.getArgument(4, Integer.class);
            IntConsumer consumer = invocation.getArgument(5, IntConsumer.class);
            for (int tick = 0; tick < executions && !cancelled.get(); tick++) {
                consumer.accept(tick);
            }
            return null;
        }).when(tasks).repeat(any(UUID.class), anyString(), anyLong(), anyLong(), anyInt(), any(IntConsumer.class));
        SkillProjectileService service = new SkillProjectileService(targeting, effects, tasks);
        SkillBallisticProjectileSpec spec = new SkillBallisticProjectileSpec(
                new Vector(0.0D, -1.0D, 0.0D), 0.14D, 14, 1000.0D, 0.75D, false, 1,
                SharedParticleDefinitions.SKILL_HUNTER_ARROW,
                SharedParticleDefinitions.SKILL_HUNTER_IMPACT
        );
        List<SkillBallisticProjectileLaunch> launches = IntStream.range(0, 81)
                .mapToObj(index -> new SkillBallisticProjectileLaunch(
                        new Location(world, index, 5.0D, 0.0D), spec
                ))
                .toList();
        AtomicInteger terminated = new AtomicInteger();

        service.launchBallisticVolley(
                player, launches, 3, 2.0D,
                (target, impact) -> { }, ignored -> terminated.incrementAndGet()
        );

        assertEquals(81, terminated.get());
        verify(targeting, times(40)).captureLineTargetSnapshot(player);
        verify(targeting, times(1134)).lineTargetHits(
                same(player), same(targetSnapshot), any(), any(), anyDouble(), anyDouble(), anyInt(), eq(true)
        );
        verify(targeting, times(1134)).blockImpactBelowY(any(), any(), anyDouble(), eq(2.0D));
        verify(tasks, times(1)).repeat(eq(playerId), anyString(), eq(0L), eq(1L), eq(41), any(IntConsumer.class));
        verify(tasks, times(1)).cancel(eq(playerId), anyString());
        ArgumentCaptor<List<SkillEffectLineSegment>> segments = ArgumentCaptor.forClass(List.class);
        verify(effects, times(40)).lines(
                any(), segments.capture(), eq(0.45D), eq(SharedParticleDefinitions.SKILL_HUNTER_ARROW)
        );
        List<Integer> segmentCounts = segments.getAllValues().stream().map(List::size).toList();
        assertEquals(3, segmentCounts.getFirst());
        assertEquals(42, segmentCounts.stream().mapToInt(Integer::intValue).max().orElseThrow());
        assertEquals(3, segmentCounts.getLast());
        assertEquals(1134, segmentCounts.stream().mapToInt(Integer::intValue).sum());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 17. ハンター アローレインの実装契約 > ### 17.2 弾道
     * 検証契約: 単発重力飛翔体は複数tickで移動後に重力を適用し、寿命終端をRANGEとして正確な位置で通知する。
     */
    @Test
    void ballisticProjectileAppliesGravityAcrossTicksAndEndsAtLifetime() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(targeting.lineTargetHits(any(), any(), any(), anyDouble(), anyDouble(), anyInt(), eq(true)))
                .thenReturn(List.of());
        doAnswer(invocation -> {
            int executions = invocation.getArgument(4, Integer.class);
            IntConsumer consumer = invocation.getArgument(5, IntConsumer.class);
            for (int tick = 0; tick < executions; tick++) {
                consumer.accept(tick);
            }
            return null;
        }).when(tasks).repeat(any(UUID.class), anyString(), anyLong(), anyLong(), anyInt(), any(IntConsumer.class));
        SkillProjectileService service = new SkillProjectileService(targeting, effects, tasks);
        SkillBallisticProjectileSpec spec = new SkillBallisticProjectileSpec(
                new Vector(1.0D, 1.0D, 0.0D), 0.5D, 3, 100.0D, 0.45D, false, 1,
                SharedParticleDefinitions.SKILL_HUNTER_ARROW,
                SharedParticleDefinitions.SKILL_HUNTER_IMPACT
        );
        AtomicReference<SkillProjectileTermination> termination = new AtomicReference<>();

        service.launchBallisticWithTermination(
                player, new Location(world, 0.0D, 0.0D, 0.0D), spec,
                (target, impact) -> { }, termination::set
        );

        assertEquals(SkillProjectileTermination.Type.RANGE, termination.get().type());
        assertEquals(3.0D, termination.get().location().getX(), 1.0E-9D);
        assertEquals(1.5D, termination.get().location().getY(), 1.0E-9D);
        verify(targeting, times(3)).blockImpact(any(), any(), anyDouble());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 17. ハンター アローレインの実装契約 > ### 17.2 弾道
     * 検証契約: 単発重力飛翔体のBLOCK終端は正確なBlock面と0.1m手前の効果中心を分けて通知する。
     */
    @Test
    void ballisticProjectileSeparatesBlockFaceFromEffectLocation() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        Player player = mock(Player.class);
        World world = mock(World.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        Location blockFace = new Location(world, 0.8D, 0.0D, 0.0D);
        when(targeting.blockImpact(any(), any(), anyDouble())).thenReturn(blockFace);
        when(targeting.lineTargetHits(any(), any(), any(), anyDouble(), anyDouble(), anyInt(), eq(false)))
                .thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.getArgument(5, IntConsumer.class).accept(0);
            return null;
        }).when(tasks).repeat(any(UUID.class), anyString(), anyLong(), anyLong(), anyInt(), any(IntConsumer.class));
        SkillProjectileService service = new SkillProjectileService(targeting, effects, tasks);
        SkillBallisticProjectileSpec spec = new SkillBallisticProjectileSpec(
                new Vector(1.0D, 0.0D, 0.0D), 0.1D, 4, 10.0D, 0.45D, false, 1,
                SharedParticleDefinitions.SKILL_HUNTER_ARROW,
                SharedParticleDefinitions.SKILL_HUNTER_IMPACT
        );
        AtomicReference<SkillProjectileTermination> termination = new AtomicReference<>();

        service.launchBallisticWithTermination(
                player, new Location(world, 0.0D, 0.0D, 0.0D), spec,
                (target, impact) -> { }, termination::set
        );

        assertEquals(SkillProjectileTermination.Type.BLOCK, termination.get().type());
        assertEquals(blockFace, termination.get().location());
        assertEquals(0.7D, termination.get().effectLocation().getX(), 1.0E-9D);
        assertEquals(0.0D, termination.get().effectLocation().getY(), 1.0E-9D);
        assertEquals(0.0D, termination.get().effectLocation().getZ(), 1.0E-9D);
        verify(effects).line(any(), eq(termination.get().effectLocation()), eq(0.45D),
                eq(SharedParticleDefinitions.SKILL_HUNTER_ARROW));
        verify(tasks).cancel(eq(playerId), anyString());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 17. ハンター アローレインの実装契約 > ### 17.2 弾道
     * 検証契約: 単発重力飛翔体のENTITY終端・軌跡終端・callbackは同じ正確な線分交点を使用する。
     */
    @Test
    void ballisticProjectileTerminatesAndDrawsAtExactEntityIntersection() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        Player player = mock(Player.class);
        World world = mock(World.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        AstEntity target = mock(AstEntity.class);
        when(target.id()).thenReturn(UUID.randomUUID());
        Location intersection = new Location(world, 0.4D, 0.2D, 0.0D);
        when(targeting.lineTargetHits(any(), any(), any(), anyDouble(), anyDouble(), anyInt(), eq(true)))
                .thenReturn(List.of(new SkillLineTargetHit(target, intersection, Math.sqrt(0.2D))));
        doAnswer(invocation -> {
            invocation.getArgument(5, IntConsumer.class).accept(0);
            return null;
        }).when(tasks).repeat(any(UUID.class), anyString(), anyLong(), anyLong(), anyInt(), any(IntConsumer.class));
        SkillProjectileService service = new SkillProjectileService(targeting, effects, tasks);
        SkillBallisticProjectileSpec spec = new SkillBallisticProjectileSpec(
                new Vector(1.0D, 0.5D, 0.0D), 0.1D, 4, 10.0D, 0.45D, false, 1,
                SharedParticleDefinitions.SKILL_HUNTER_ARROW,
                SharedParticleDefinitions.SKILL_HUNTER_IMPACT
        );
        AtomicReference<Location> callbackImpact = new AtomicReference<>();
        AtomicReference<SkillProjectileTermination> termination = new AtomicReference<>();

        service.launchBallisticWithTermination(
                player, new Location(world, 0.0D, 0.0D, 0.0D), spec,
                (hit, impact) -> callbackImpact.set(impact), termination::set
        );

        assertEquals(intersection, callbackImpact.get());
        assertEquals(intersection, termination.get().location());
        verify(effects).line(any(), eq(intersection), eq(0.45D), eq(SharedParticleDefinitions.SKILL_HUNTER_ARROW));
        verify(tasks).cancel(eq(playerId), anyString());
        assertTrue(termination.get().type() == SkillProjectileTermination.Type.ENTITY);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 19. ヒールアローの実装契約 > ### 19.2 演出・入手・テスト契約
     * 検証契約: 実SkillTargetingServiceを通した弾道は、Block面より手前のMobへ命中しながら飛翔を継続し、Block面より後ろのMobを除外して正確なBlock地点でBLOCK終端とする。
     */
    @Test
    void ballisticProjectileUsesActualMobAndBlockCollisionOrdering() {
        World world = mock(World.class);
        Player player = mock(Player.class);
        MobService mobService = mock(MobService.class);
        MobTemplate template = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D).template();
        RayTraceResult blockHit = mock(RayTraceResult.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(blockHit.getHitPosition()).thenReturn(new Vector(1.0D, 1.0D, 0.0D));
        when(world.rayTraceBlocks(
                any(Location.class), any(Vector.class), anyDouble(), eq(FluidCollisionMode.NEVER), eq(true)
        )).thenReturn(blockHit);

        MobInstance mobBeforeBlock = actualTarget(template, world, 0.8D);
        when(mobService.getInstances()).thenReturn(List.of(mobBeforeBlock));
        when(mobService.getInstance(mobBeforeBlock.instanceId())).thenReturn(mobBeforeBlock);
        SkillTargetingService targeting = new SkillTargetingService(mobService);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        runOneTick(tasks);
        SkillProjectileService service = new SkillProjectileService(targeting, effects, tasks);
        SkillBallisticProjectileSpec projectile = ballisticSpec();
        AtomicReference<SkillProjectileTermination> mobTermination = new AtomicReference<>();
        AtomicReference<AstEntity> mobHit = new AtomicReference<>();

        service.launchBallisticWithTermination(
                player,
                new Location(world, 0.0D, 1.0D, 0.0D),
                projectile,
                (target, ignored) -> mobHit.set(target),
                mobTermination::set
        );

        assertEquals(mobBeforeBlock, mobHit.get().mob());
        assertEquals(SkillProjectileTermination.Type.BLOCK, mobTermination.get().type());
        assertEquals(1.0D, mobTermination.get().location().getX(), 0.0001D);

        MobInstance mobBehindBlock = actualTarget(template, world, 2.0D);
        when(mobService.getInstances()).thenReturn(List.of(mobBehindBlock));
        when(mobService.getInstance(mobBehindBlock.instanceId())).thenReturn(mobBehindBlock);
        AtomicReference<SkillProjectileTermination> blockTermination = new AtomicReference<>();

        service.launchBallisticWithTermination(
                player,
                new Location(world, 0.0D, 1.0D, 0.0D),
                projectile,
                (target, ignored) -> { },
                blockTermination::set
        );

        assertEquals(SkillProjectileTermination.Type.BLOCK, blockTermination.get().type());
        assertEquals(1.0D, blockTermination.get().location().getX(), 0.0001D);
    }

    private static MobInstance actualTarget(MobTemplate template, World world, double x) {
        MobInstance target = mock(MobInstance.class);
        when(target.instanceId()).thenReturn(UUID.randomUUID());
        when(target.template()).thenReturn(template);
        when(target.state()).thenReturn(MobState.IDLE);
        when(target.bukkitEntityId()).thenReturn(null);
        when(target.currentLocation()).thenReturn(new Location(world, x, 0.0D, 0.0D));
        return target;
    }

    private static void runOneTick(SkillTaskService tasks) {
        doAnswer(invocation -> {
            IntConsumer action = invocation.getArgument(5, IntConsumer.class);
            action.accept(0);
            return null;
        }).when(tasks).repeat(any(UUID.class), anyString(), anyLong(), anyLong(), anyInt(), any(IntConsumer.class));
    }

    private static SkillBallisticProjectileSpec ballisticSpec() {
        return new SkillBallisticProjectileSpec(
                new Vector(1.0D, 0.0D, 0.0D),
                0.14D, 4, 10.0D, 0.45D, true, Integer.MAX_VALUE,
                SharedParticleDefinitions.HUNTER_HEAL_ARROW_TRAIL,
                SharedParticleDefinitions.HUNTER_HEAL_ARROW_IMPACT
        );
    }

    private static Fixture fixture() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        Player player = mock(Player.class);
        Location origin = mock(Location.class);
        when(origin.clone()).thenReturn(origin);
        when(origin.distance(any(Location.class))).thenAnswer(invocation ->
                invocation.getArgument(0, Location.class).getX());
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(targeting.clippedEnd(any(), any(), anyDouble()))
                .thenReturn(new Location(null, 1.0D, 0.0D, 0.0D));
        when(targeting.inLine(any(), any(), any(), anyDouble(), anyDouble(), anyInt())).thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.getArgument(5, IntConsumer.class).accept(0);
            return null;
        }).when(tasks).repeat(any(UUID.class), anyString(), anyLong(), anyLong(), anyInt(), any(IntConsumer.class));
        return new Fixture(new SkillProjectileService(targeting, effects, tasks), targeting, player, origin);
    }

    private static SkillProjectileSpec spec() {
        return new SkillProjectileSpec(
                1.0D, 1.0D, 0.45D, false, 1,
                SharedParticleDefinitions.SKILL_HUNTER_ARROW,
                SharedParticleDefinitions.SKILL_HUNTER_IMPACT
        );
    }

    private record Fixture(
            SkillProjectileService service,
            SkillTargetingService targeting,
            Player player,
            Location origin
    ) {
    }
}
