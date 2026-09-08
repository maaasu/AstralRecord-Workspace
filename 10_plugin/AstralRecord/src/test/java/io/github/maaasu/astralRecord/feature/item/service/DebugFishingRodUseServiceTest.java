package io.github.maaasu.astralRecord.feature.item.service;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DebugFishingRodUseServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 釣り竿先端は視線の前方・右・上の基底オフセットから決まり、視線正面では右上前方へ移動する。
     */
    @Test
    void rodTipUsesForwardRightAndUpBasisFromEye() {
        Location tip = DebugFishingRodUseService.resolveRodTip(
            new Location(null, 0.0D, 10.0D, 0.0D),
            new Vector(0.0D, 0.0D, -1.0D)
        );

        assertNotNull(tip);
        assertEquals(0.35D, tip.getX(), 0.0001D);
        assertEquals(10.20D, tip.getY(), 0.0001D);
        assertEquals(-0.35D, tip.getZ(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 発射中の糸の中点は始点と終点を結ぶ直線上ではなく、下方へしなる二次ベジェ曲線上に配置される。
     */
    @Test
    void ropePointHasSlackBelowTheStraightMidpoint() {
        Location point = DebugFishingRodUseService.ropePoint(
            new Location(null, 0.0D, 0.0D, 0.0D),
            new Location(null, 5.0D, -2.0D, 0.0D),
            new Location(null, 10.0D, 0.0D, 0.0D),
            0.5D
        );

        assertEquals(5.0D, point.getX(), 0.0001D);
        assertEquals(-1.0D, point.getY(), 0.0001D);
        assertEquals(0.0D, point.getZ(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: キャスト距離を100000 block相当へ設定しても表示線の長さを同じ値で保持し、プラグイン側の距離上限で切り詰めない。
     */
    @Test
    void lineTransformKeepsAValidLongSegmentWithoutCastDistanceClamp() {
        Transformation transformation = DebugFishingRodUseService.lineTransformation(
            new Location(null, 0.0D, 0.0D, 0.0D),
            new Location(null, 100_000.0D, 0.0D, 0.0D)
        );

        assertNotNull(transformation);
        assertEquals(100_000.0F, transformation.getScale().x, 0.01F);
        assertEquals(DebugFishingRodUseService.LINE_THICKNESS, transformation.getScale().y, 0.0001F);
        assertEquals(DebugFishingRodUseService.LINE_THICKNESS, transformation.getScale().z, 0.0001F);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 発射中の針が固体blockへ到達した場合、そのblockの手前で停止し、保持状態へ遷移する。
     */
    @Test
    void outboundHookStopsAtSolidBlock() {
        World world = mock(World.class);
        Block solidBlock = mock(Block.class);
        when(solidBlock.getType()).thenReturn(Material.STONE);
        when(solidBlock.isPassable()).thenReturn(false);
        stubRayTrace(world, solidBlock, new Vector(1.0D, 64.5D, 0.5D));
        Location start = new Location(world, 0.5D, 64.5D, 0.5D);
        DebugFishingRodUseService.ActiveCast active = activeCast(start, new Location(world, 4.5D, 64.5D, 0.5D));

        assertFalse(DebugFishingRodUseService.advanceHook(active));
        assertEquals(DebugFishingRodUseService.CastPhase.HOLDING, active.phase);
        assertEquals(1.0D, active.currentHook.getX(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 水blockへの着水後は沈下状態へ遷移し、次のtickから水中方向へ少しずつ移動する。
     */
    @Test
    void waterImpactStartsGradualSinking() {
        World world = mock(World.class);
        Block waterBlock = mock(Block.class);
        when(waterBlock.getType()).thenReturn(Material.WATER);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(waterBlock);
        stubRayTrace(world, waterBlock, new Vector(1.0D, 64.5D, 0.5D));
        Location start = new Location(world, 0.5D, 64.5D, 0.5D);
        DebugFishingRodUseService.ActiveCast active = activeCast(start, new Location(world, 4.5D, 64.5D, 0.5D));

        assertFalse(DebugFishingRodUseService.advanceHook(active));
        assertEquals(DebugFishingRodUseService.CastPhase.SINKING, active.phase);
        double impactY = active.currentHook.getY();

        assertFalse(DebugFishingRodUseService.advanceHook(active));
        assertEquals(impactY - DebugFishingRodUseService.WATER_SINK_SPEED_PER_TICK, active.currentHook.getY(), 0.0001D);
        assertEquals(1, active.sinkTicks);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: キャスト距離は糸の最大長であり、最大長へ到達しても空中で保持状態へ遷移せず、重力で落下する。
     */
    @Test
    void outboundHookContinuesFallingAfterReachingMaxLineLength() {
        World world = mock(World.class);
        when(world.rayTraceBlocks(
            any(Location.class),
            any(Vector.class),
            anyDouble(),
            eq(FluidCollisionMode.ALWAYS),
            eq(false)
        )).thenReturn(null);
        Location rodTip = new Location(world, 0.5D, 64.5D, 0.5D);
        DebugFishingRodUseService.ActiveCast active = activeCast(
            rodTip,
            new Location(world, 4.5D, 64.5D, 0.5D)
        );

        for (int tick = 0; tick < 4; tick++) {
            assertFalse(DebugFishingRodUseService.advanceHook(active));
        }

        assertEquals(DebugFishingRodUseService.CastPhase.OUTBOUND, active.phase);
        assertTrue(active.currentHook.getY() < rodTip.getY());
        assertTrue(active.currentHook.distance(rodTip) <= 4.0001D);
        double yAfterFourTicks = active.currentHook.getY();

        assertFalse(DebugFishingRodUseService.advanceHook(active));
        assertTrue(active.currentHook.getY() < yAfterFourTicks);
        assertTrue(active.currentHook.distance(rodTip) <= 4.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 最大長まで伸びた糸が水中へ沈むとき、糸長を超えず、針は下方かつ竿先側へ移動する。
     */
    @Test
    void sinkingHookMovesTowardRodTipWhenLineIsFullyExtended() {
        World world = mock(World.class);
        Block waterBlock = mock(Block.class);
        when(waterBlock.getType()).thenReturn(Material.WATER);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(waterBlock);
        Location rodTip = new Location(world, 0.5D, 64.5D, 0.5D);
        Location maxLengthPoint = new Location(world, 4.5D, 64.5D, 0.5D);
        DebugFishingRodUseService.ActiveCast active = activeCast(rodTip, maxLengthPoint);
        active.currentHook = maxLengthPoint.clone();
        active.phase = DebugFishingRodUseService.CastPhase.SINKING;

        assertFalse(DebugFishingRodUseService.advanceHook(active));

        assertEquals(DebugFishingRodUseService.CastPhase.SINKING, active.phase);
        assertTrue(active.currentHook.getX() < maxLengthPoint.getX());
        assertTrue(active.currentHook.getY() < maxLengthPoint.getY());
        assertTrue(active.currentHook.distance(rodTip) <= 4.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 再右クリックで回収状態へ切り替わった針は、竿先へ向かって移動し、到達tickで回収条件を満たす。
     */
    @Test
    void retractingHookArrivesAtRodTip() {
        World world = mock(World.class);
        Location rodTip = new Location(world, 0.5D, 64.5D, 0.5D);
        DebugFishingRodUseService.ActiveCast active = activeCast(
            new Location(world, 4.5D, 64.5D, 0.5D),
            rodTip
        );
        active.phase = DebugFishingRodUseService.CastPhase.RETRACTING;

        assertFalse(DebugFishingRodUseService.advanceHook(active));
        assertEquals(2.5D, active.currentHook.getX(), 0.0001D);
        assertTrue(DebugFishingRodUseService.advanceHook(active));
        assertEquals(rodTip, active.currentHook);
    }

    private static DebugFishingRodUseService.ActiveCast activeCast(Location start, Location target) {
        BlockDisplay hookDisplay = mock(BlockDisplay.class);
        BlockDisplay ropeDisplay = mock(BlockDisplay.class);
        return new DebugFishingRodUseService.ActiveCast(
            "test-equipment-instance",
            start.clone(),
            start.clone(),
            target.clone(),
            false,
            hookDisplay,
            List.of(ropeDisplay)
        );
    }

    private static void stubRayTrace(World world, Block block, Vector hitPosition) {
        RayTraceResult hit = mock(RayTraceResult.class);
        when(hit.getHitBlock()).thenReturn(block);
        when(hit.getHitPosition()).thenReturn(hitPosition);
        when(world.rayTraceBlocks(
            any(Location.class),
            any(Vector.class),
            eq(1.5D),
            eq(FluidCollisionMode.ALWAYS),
            eq(false)
        )).thenReturn(hit);
    }
}
