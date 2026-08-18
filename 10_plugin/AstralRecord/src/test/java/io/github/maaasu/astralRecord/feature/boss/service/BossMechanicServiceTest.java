package io.github.maaasu.astralRecord.feature.boss.service;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BossMechanicServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: 最大HP比70%以下を第2段階、35%以下を第3段階として境界値を含めて判定する。
     */
    @Test
    void healthThresholdsIncludeSeventyAndThirtyFivePercentBoundaries() {
        assertEquals(1, BossMechanicProfile.phaseFor(70.01D, 100.0D));
        assertEquals(2, BossMechanicProfile.phaseFor(70.0D, 100.0D));
        assertEquals(2, BossMechanicProfile.phaseFor(35.01D, 100.0D));
        assertEquals(3, BossMechanicProfile.phaseFor(35.0D, 100.0D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: Dungeon worldでは対象Bossの地形破壊を拒否し、Dungeon外では対象Bossだけを許可する。
     */
    @Test
    void terrainPermissionRejectsDungeonAndUnknownBosses() {
        assertTrue(BossTerrainPolicy.mayBreak(BossMechanicProfile.TWILIGHT_COLOSSUS, false));
        assertTrue(BossTerrainPolicy.mayBreak(BossMechanicProfile.FENRIR_WORLDBREAKER, false));
        assertFalse(BossTerrainPolicy.mayBreak(BossMechanicProfile.TWILIGHT_COLOSSUS, true));
        assertFalse(BossTerrainPolicy.mayBreak("unknown_boss", false));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: 自然地形を許可し、コンテナ・基盤ブロック・機能ブロックを破壊対象から除外する。
     */
    @Test
    void terrainWhitelistExcludesProtectedAndFunctionalBlocks() {
        assertTrue(BossTerrainPolicy.isBreakable(Material.STONE));
        assertTrue(BossTerrainPolicy.isBreakable(Material.DIRT));
        assertFalse(BossTerrainPolicy.isBreakable(Material.BEDROCK));
        assertFalse(BossTerrainPolicy.isBreakable(Material.CHEST));
        assertFalse(BossTerrainPolicy.isBreakable(Material.COMMAND_BLOCK));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: 線形予兆は前方の長さと幅の内側だけを命中範囲として扱う。
     */
    @Test
    void lineAreaIncludesOnlyForwardPointsWithinLengthAndWidth() {
        Location origin = new Location(null, 0.0D, 64.0D, 0.0D);
        Vector direction = new Vector(0.0D, 0.0D, 1.0D);

        assertTrue(BossMechanicService.insideLine(
            new Location(null, 1.4D, 64.0D, 8.0D), origin, direction, 12.0D, 1.5D
        ));
        assertFalse(BossMechanicService.insideLine(
            new Location(null, 1.6D, 64.0D, 8.0D), origin, direction, 12.0D, 1.5D
        ));
        assertFalse(BossMechanicService.insideLine(
            new Location(null, 0.0D, 64.0D, -1.0D), origin, direction, 12.0D, 1.5D
        ));
        assertFalse(BossMechanicService.insideLine(
            new Location(null, 0.0D, 64.0D, 12.1D), origin, direction, 12.0D, 1.5D
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: 交差ルーンの表示地点は、命中帯の半幅1ブロックに一致する中心線と両境界線を含む。
     */
    @Test
    void runeLanesParticleLocationsIncludeDamageWidthBoundaries() {
        List<Location> locations = BossMechanicService.crossParticleLocations(
            new Location(null, 0.0D, 64.0D, 0.0D),
            new Vector(0.0D, 0.0D, 1.0D),
            32.0D,
            1.0D
        );

        assertTrue(locations.contains(new Location(null, 0.0D, 64.15D, 8.0D)));
        assertTrue(locations.contains(new Location(null, 1.0D, 64.15D, 8.0D)));
        assertTrue(locations.contains(new Location(null, -1.0D, 64.15D, 8.0D)));
        assertTrue(locations.contains(new Location(null, 8.0D, 64.15D, 1.0D)));
        assertTrue(locations.contains(new Location(null, 8.0D, 64.15D, -1.0D)));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: 交差ルーンはレイトレースした壁の地点で止まり、壁なし時は32ブロックを超えず、非通過ブロックを無視しない。
     */
    @Test
    void runeLanesResolveWallHitAndSafeMaximumThroughRayTrace() {
        World world = mock(World.class);
        Location origin = new Location(world, 0.0D, 64.0D, 0.0D);
        Vector direction = new Vector(0.0D, 0.0D, 8.0D);
        RayTraceResult hit = mock(RayTraceResult.class);
        when(hit.getHitPosition()).thenReturn(new Vector(0.0D, 64.0D, 9.5D));
        when(world.rayTraceBlocks(
            any(Location.class),
            any(Vector.class),
            eq(32.0D),
            eq(FluidCollisionMode.NEVER),
            eq(true)
        )).thenReturn(hit);

        assertEquals(9.5D, BossMechanicService.wallLimitedLength(origin, direction, 48.0D), 0.0001D);

        when(world.rayTraceBlocks(
            any(Location.class),
            any(Vector.class),
            eq(32.0D),
            eq(FluidCollisionMode.NEVER),
            eq(true)
        )).thenReturn(null);
        assertEquals(32.0D, BossMechanicService.wallLimitedLength(origin, direction, 48.0D), 0.0001D);

        ArgumentCaptor<Vector> directionCaptor = ArgumentCaptor.forClass(Vector.class);
        verify(world, times(2)).rayTraceBlocks(
            eq(origin),
            directionCaptor.capture(),
            eq(32.0D),
            eq(FluidCollisionMode.NEVER),
            eq(true)
        );
        directionCaptor.getAllValues().forEach(captured -> {
            assertEquals(0.0D, captured.getX(), 0.0001D);
            assertEquals(0.0D, captured.getY(), 0.0001D);
            assertEquals(1.0D, captured.getZ(), 0.0001D);
        });
    }
}
