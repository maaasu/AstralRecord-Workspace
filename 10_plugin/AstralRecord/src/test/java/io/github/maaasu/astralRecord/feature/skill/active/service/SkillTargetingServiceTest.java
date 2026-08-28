package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillLineTargetHit;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillTargetingServiceTest {

    private static final BoundingBox STANDARD_MOB = new BoundingBox(
            -0.3D, 0.0D, 4.7D,
            0.3D, 1.8D, 5.3D
    );

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: 水平eye rayとMob実body bounding boxの交差をhitとする。
     */
    @Test
    void horizontalEyeRayHitsTheMobsActualBodyBounds() {
        assertTrue(SkillTargetingService.intersectsLine(
                STANDARD_MOB,
                new Vector(0.0D, 1.62D, 0.0D),
                new Vector(0.0D, 0.0D, 1.0D),
                10.0D,
                0.45D
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: line capsule半径で拡張したbounds外のtargetをhitにしない。
     */
    @Test
    void capsuleRadiusDoesNotHitTargetsOutsideItsExpandedBounds() {
        assertFalse(SkillTargetingService.intersectsLine(
                STANDARD_MOB,
                new Vector(0.0D, 2.4D, 0.0D),
                new Vector(0.0D, 0.0D, 1.0D),
                10.0D,
                0.45D
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: line originより後方のtargetをhitにしない。
     */
    @Test
    void lineDoesNotHitTargetsBehindItsOrigin() {
        assertFalse(SkillTargetingService.intersectsLine(
                STANDARD_MOB,
                new Vector(0.0D, 1.0D, 10.0D),
                new Vector(0.0D, 0.0D, 1.0D),
                10.0D,
                0.5D
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: target center距離でなくrayがbody前面へ入る交差距離でhit順を決める。
     */
    @Test
    void intersectionDistanceOrdersByFrontFaceInsteadOfTargetCenter() {
        BoundingBox largeFrontTarget = new BoundingBox(-1.0D, 0.0D, 2.0D, 1.0D, 3.0D, 10.0D);
        BoundingBox smallRearTarget = new BoundingBox(-0.3D, 0.0D, 4.0D, 0.3D, 1.8D, 4.5D);
        Vector origin = new Vector(0.0D, 1.0D, 0.0D);
        Vector direction = new Vector(0.0D, 0.0D, 1.0D);

        double largeDistance = SkillTargetingService.lineIntersectionDistance(
                largeFrontTarget, origin, direction, 20.0D, 0.0D
        );
        double smallDistance = SkillTargetingService.lineIntersectionDistance(
                smallRearTarget, origin, direction, 20.0D, 0.0D
        );

        assertEquals(2.0D, largeDistance, 1.0E-9D);
        assertEquals(4.0D, smallDistance, 1.0E-9D);
        assertTrue(largeDistance < smallDistance);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック
     * 検証契約: Block面の直前で capsule がMobへ交差した場合だけ命中とし、Block面と同距離の交差はBlockを優先する。
     */
    @Test
    void lineBeforeBlockIncludesOnlyMobCollisionsStrictlyBeforeBlockImpact() {
        World world = mock(World.class);
        Player player = mock(Player.class);
        MobService mobService = mock(MobService.class);
        MobInstance mob = mock(MobInstance.class);
        MobTemplate template = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D).template();
        when(player.getWorld()).thenReturn(world);
        when(mobService.getInstances()).thenReturn(List.of(mob));
        when(mob.state()).thenReturn(MobState.IDLE);
        when(mob.template()).thenReturn(template);
        when(mob.instanceId()).thenReturn(UUID.randomUUID());
        when(mob.bukkitEntityId()).thenReturn(null);

        SkillTargetingService service = new SkillTargetingService(mobService);
        Location origin = new Location(world, 0.0D, 1.0D, 0.0D);
        when(mob.currentLocation()).thenReturn(new Location(world, 1.349D, 0.0D, 0.0D));

        List<AstEntity> beforeBlock = service.inLineBeforeBlock(
                player, origin, new Vector(1.0D, 0.0D, 0.0D), 0.9D, 0.0D, 1
        );
        when(mob.currentLocation()).thenReturn(new Location(world, 1.35D, 0.0D, 0.0D));
        List<AstEntity> atBlock = service.inLineBeforeBlock(
                player, origin, new Vector(1.0D, 0.0D, 0.0D), 0.9D, 0.0D, 1
        );

        assertEquals(1, beforeBlock.size());
        assertTrue(atBlock.isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 17. ハンター アローレインの実装契約 > ### 17.2 弾道
     * 検証契約: 解決済み線分はMob基準位置ではなく、拡張body前面との正確な最初の交点を返す。
     */
    @Test
    void lineTargetHitReturnsExactExpandedBodyIntersection() {
        World world = mock(World.class);
        Player player = mock(Player.class);
        MobService mobService = mock(MobService.class);
        MobTemplate template = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D).template();
        MobInstance mob = mockMob(template, world, 0.0D, 5.0D);
        when(player.getWorld()).thenReturn(world);
        when(mobService.getInstances()).thenReturn(List.of(mob));
        when(mobService.getInstance(mob.instanceId())).thenReturn(mob);

        List<SkillLineTargetHit> hits = new SkillTargetingService(mobService).lineTargetHits(
                player,
                new Location(world, 0.0D, 1.0D, 0.0D),
                new Vector(0.0D, 0.0D, 1.0D),
                10.0D,
                0.25D,
                1,
                true
        );

        assertEquals(1, hits.size());
        assertEquals(4.3D, hits.getFirst().distance(), 1.0E-9D);
        assertEquals(4.3D, hits.getFirst().location().getZ(), 1.0E-9D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: 同一tickの複数線分はMob一覧とbody boundsのsnapshotを共有しつつ、登録解除済みMobを後続線分から除外する。
     */
    @Test
    void lineTargetSnapshotReusesMobCandidatesAcrossSegments() {
        World world = mock(World.class);
        Player player = mock(Player.class);
        MobService mobService = mock(MobService.class);
        MobTemplate template = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D).template();
        MobInstance mob = mockMob(template, world, 0.0D, 5.0D);
        when(player.getWorld()).thenReturn(world);
        when(mobService.getInstances()).thenReturn(List.of(mob));
        when(mobService.getInstance(mob.instanceId())).thenReturn(mob);
        SkillTargetingService service = new SkillTargetingService(mobService);

        SkillTargetingService.LineTargetSnapshot snapshot = service.captureLineTargetSnapshot(player);
        for (int index = 0; index < 2; index++) {
            List<SkillLineTargetHit> hits = service.lineTargetHits(
                    player,
                    snapshot,
                    new Location(world, 0.0D, 1.0D, 0.0D),
                    new Vector(0.0D, 0.0D, 1.0D),
                    10.0D,
                    0.25D,
                    1,
                    true
            );
            assertEquals(1, hits.size());
            assertEquals(4.3D, hits.getFirst().distance(), 1.0E-9D);
        }

        when(mobService.getInstance(mob.instanceId())).thenReturn(null);
        assertTrue(service.lineTargetHits(
                player,
                snapshot,
                new Location(world, 0.0D, 1.0D, 0.0D),
                new Vector(0.0D, 0.0D, 1.0D),
                10.0D,
                0.25D,
                1,
                true
        ).isEmpty());
        verify(mobService, times(1)).getInstances();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 17. ハンター アローレインの実装契約 > ### 17.2 弾道
     * 検証契約: 初弾着弾Y以上の線分ではBlock判定を行わず、下降してY未満へ入った区間だけを通常のBlock ray traceへ渡す。
     */
    @Test
    void blockImpactBelowYTracesOnlyTheSegmentBelowTheOpeningImpactHeight() {
        World world = mock(World.class);
        MobService mobService = mock(MobService.class);
        RayTraceResult floorHit = mock(RayTraceResult.class);
        when(floorHit.getHitPosition()).thenReturn(new Vector(0.0D, 0.0D, 0.0D));
        when(world.rayTraceBlocks(
                any(Location.class), any(Vector.class), anyDouble(), eq(FluidCollisionMode.NEVER), eq(true)
        )).thenReturn(floorHit);
        SkillTargetingService service = new SkillTargetingService(mobService);

        assertNull(service.blockImpactBelowY(
                new Location(world, 0.0D, 5.0D, 0.0D),
                new Vector(1.0D, 0.0D, 0.0D),
                3.0D,
                2.0D
        ));
        Location impact = service.blockImpactBelowY(
                new Location(world, 0.0D, 5.0D, 0.0D),
                new Vector(0.0D, -1.0D, 0.0D),
                10.0D,
                2.0D
        );

        assertEquals(0.0D, impact.getY(), 1.0E-9D);
        ArgumentCaptor<Location> traceOrigin = ArgumentCaptor.forClass(Location.class);
        verify(world, times(1)).rayTraceBlocks(
                traceOrigin.capture(), any(Vector.class), anyDouble(),
                eq(FluidCollisionMode.NEVER), eq(true)
        );
        assertTrue(traceOrigin.getValue().getY() < 2.0D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: blockHitはray traceの正確な衝突位置とBlockFaceの外向き法線を返す。
     */
    @Test
    void blockHitReturnsExactImpactAndBlockFaceNormal() {
        World world = mock(World.class);
        RayTraceResult rayHit = mock(RayTraceResult.class);
        when(rayHit.getHitPosition()).thenReturn(new Vector(1.25D, 2.5D, 3.75D));
        when(rayHit.getHitBlockFace()).thenReturn(BlockFace.EAST);
        when(world.rayTraceBlocks(
                any(Location.class), any(Vector.class), eq(4.0D), eq(FluidCollisionMode.NEVER), eq(true)
        )).thenReturn(rayHit);

        SkillTargetingService.BlockHit hit = new SkillTargetingService(mock(MobService.class)).blockHit(
                new Location(world, 0.0D, 2.5D, 3.75D), new Vector(2.0D, 0.0D, 0.0D), 4.0D
        );

        assertEquals(1.25D, hit.location().getX(), 1.0E-9D);
        assertEquals(new Vector(1.0D, 0.0D, 0.0D), hit.normal());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 9. 冒険者マナバーストの実装契約 > ### 9.1 数値と対象形状
     * 検証契約: inConeは視線前方のMobだけを発動者から近い順に選び、最大対象数で打ち切る。
     */
    @Test
    void coneSelectsForwardMobTargetsNearestFirstAndRespectsMaxTargets() {
        World world = mock(World.class);
        Player player = mock(Player.class);
        MobService mobService = mock(MobService.class);
        MobTemplate template = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D).template();
        MobInstance far = mockMob(template, world, 0.0D, 5.0D);
        MobInstance behind = mockMob(template, world, 0.0D, -3.0D);
        MobInstance side = mockMob(template, world, 4.0D, 4.0D);
        MobInstance near = mockMob(template, world, 0.0D, 3.0D);
        when(mobService.getInstances()).thenReturn(List.of(far, behind, side, near));
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0.0D, 0.0D, 0.0D));
        Location eye = new Location(world, 0.0D, 1.6D, 0.0D);
        eye.setDirection(new Vector(0.0D, 0.0D, 1.0D));
        when(player.getEyeLocation()).thenReturn(eye);

        List<AstEntity> targets = new SkillTargetingService(mobService).inCone(
                player, 7.0D, 60.0D, 2, true
        );

        assertEquals(2, targets.size());
        assertEquals(near.instanceId(), targets.get(0).id());
        assertEquals(far.instanceId(), targets.get(1).id());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 9. 冒険者マナバーストの実装契約 > ### 9.1 数値と対象形状
     * 検証契約: inConeは遮蔽判定を有効にした場合だけ、対象中心までのブロック衝突を理由に対象を除外する。
     */
    @Test
    void coneHonorsBlockLineOfSightWhenRequested() {
        World world = mock(World.class);
        Player player = mock(Player.class);
        MobService mobService = mock(MobService.class);
        MobTemplate template = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D).template();
        MobInstance target = mockMob(template, world, 0.0D, 3.0D);
        when(mobService.getInstances()).thenReturn(List.of(target));
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0.0D, 0.0D, 0.0D));
        Location eye = new Location(world, 0.0D, 1.6D, 0.0D);
        eye.setDirection(new Vector(0.0D, 0.0D, 1.0D));
        when(player.getEyeLocation()).thenReturn(eye);
        when(world.rayTraceBlocks(
                any(Location.class), any(Vector.class), anyDouble(), eq(FluidCollisionMode.NEVER), eq(true)
        )).thenReturn(mock(RayTraceResult.class));

        SkillTargetingService service = new SkillTargetingService(mobService);

        assertTrue(service.inCone(player, 7.0D, 60.0D, 6, false).size() == 1);
        assertTrue(service.inCone(player, 7.0D, 60.0D, 6, true).isEmpty());
    }

    private static MobInstance mockMob(
            MobTemplate template,
            World world,
            double x,
            double z
    ) {
        MobInstance mob = mock(MobInstance.class);
        when(mob.state()).thenReturn(MobState.IDLE);
        when(mob.template()).thenReturn(template);
        when(mob.instanceId()).thenReturn(UUID.randomUUID());
        when(mob.bukkitEntityId()).thenReturn(null);
        when(mob.currentLocation()).thenReturn(new Location(world, x, 0.0D, z));
        return mob;
    }
}
