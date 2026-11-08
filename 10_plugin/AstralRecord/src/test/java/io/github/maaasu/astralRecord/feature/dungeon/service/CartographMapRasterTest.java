package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonMapRoomState;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CartographMapRasterTest {
    private final DungeonLayout layout = new DungeonLayout(1L, 112, 64, 64, 8, List.of(
            new DungeonLayout.Room(0, new DungeonLayout.Rect(12, 26, 22, 36),
                    DungeonRoomShape.CYLINDER, DungeonLayout.RoomRole.START, 0),
            new DungeonLayout.Room(1, new DungeonLayout.Rect(70, 26, 82, 36),
                    DungeonRoomShape.RECTANGLE, DungeonLayout.RoomRole.NORMAL, 1)
    ), List.of(
            new DungeonLayout.Connection(0, 0, 1,
                    List.of(new DungeonLayout.Point(17, 31), new DungeonLayout.Point(76, 31)))
    ), 0, 1);

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ > ### 8.3 手持ち地図
     * 検証契約: 地図の北を固定し、部屋外でも本人の X/Z と水平の向きを矢印に反映する。
     */
    @Test
    void markerFollowsPositionAndYawOnNorthUpMap() {
        var raster = new CartographMapRaster(layout, Map.of(), null);
        var south = raster.marker(36.5, 31.5, 0);
        var north = raster.marker(36.5, 31.5, 180);
        var northeast = raster.marker(39.5, 27.5, -135);

        assertEquals(south.x(), north.x());
        assertEquals(south.z(), north.z());
        assertEquals(south.x() + 3, northeast.x());
        assertEquals(south.z() - 4, northeast.z());
        assertEquals(northeast, raster.marker(39.5, 27.5, 225));
        assertEquals(Color.WHITE.getRGB(), south.paintedPatch(raster.background()).getRGB(5, 7));
        assertEquals(Color.WHITE.getRGB(), north.paintedPatch(raster.background()).getRGB(5, 2));
        assertNotEquals(Color.WHITE.getRGB(), south.paintedPatch(raster.background()).getRGB(5, 2));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ > ### 8.3 手持ち地図
     * 検証契約: 部屋を解放すると、その部屋と接続通路の表示が両版対応の地図色で更新される。
     */
    @Test
    void openingRoomChangesItsOutlineAndCorridor() {
        var locked = new CartographMapRaster(layout, Map.of(0, DungeonMapRoomState.CLEARED), null);
        var opened = new CartographMapRaster(layout, Map.of(
                0, DungeonMapRoomState.CLEARED, 1, DungeonMapRoomState.AVAILABLE), null);
        var corridor = opened.marker(40, 31, 0);
        var destination = opened.marker(70, 31, 0);

        assertNotEquals(locked.background().getRGB(corridor.x(), corridor.z()),
                opened.background().getRGB(corridor.x(), corridor.z()));
        assertNotEquals(locked.background().getRGB(destination.x(), destination.z()),
                opened.background().getRGB(destination.x(), destination.z()));
        assertEquals(locked.background().getRGB(0, 0), opened.background().getRGB(0, 0));
    }
}
