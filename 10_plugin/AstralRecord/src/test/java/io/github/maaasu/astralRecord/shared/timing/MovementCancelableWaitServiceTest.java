package io.github.maaasu.astralRecord.shared.timing;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MovementCancelableWaitServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/07_1-モデル定義.md
     * 章・見出し: # 07_1-モデル定義 > ## 4. ステータス種別 > ### 4.7 回復・ユーティリティ系
     * 検証契約: 消耗品使用時移動許容距離は開始地点からの距離で判定し、境界値と同じ距離ではキャンセルしない。
     */
    @Test
    void movementAllowanceUsesTheFixedStartLocationAndAllowsItsBoundary() {
        World world = mock(World.class);
        Location start = new Location(world, 10.0D, 64.0D, -5.0D);

        assertFalse(MovementCancelableWaitService.hasMoved(
            new Location(world, 12.0D, 64.0D, -5.0D), start, 2.0D * 2.0D));
        assertTrue(MovementCancelableWaitService.hasMoved(
            new Location(world, 12.001D, 64.0D, -5.0D), start, 2.0D * 2.0D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/07_1-モデル定義.md
     * 章・見出し: # 07_1-モデル定義 > ## 4. ステータス種別 > ### 4.7 回復・ユーティリティ系
     * 検証契約: 許容距離が0の場合の従来境界は0.2ブロックであり、0.2ブロックちょうどではキャンセルしない。
     */
    @Test
    void legacyMovementAllowanceAllowsExactlyPointTwoBlocks() {
        World world = mock(World.class);
        Location start = new Location(world, 0.0D, 64.0D, 0.0D);
        double legacyDistance = MovementCancelableWaitService.DEFAULT_MOVE_CANCEL_DISTANCE_BLOCKS;

        assertFalse(MovementCancelableWaitService.hasMoved(
            new Location(world, legacyDistance, 64.0D, 0.0D), start, legacyDistance * legacyDistance));
        assertTrue(MovementCancelableWaitService.hasMoved(
            new Location(world, legacyDistance + 0.001D, 64.0D, 0.0D), start, legacyDistance * legacyDistance));
    }
}
