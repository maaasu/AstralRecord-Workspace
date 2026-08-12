package io.github.maaasu.astralRecord.feature.boss.service;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
