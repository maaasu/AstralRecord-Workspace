package io.github.maaasu.astralRecord.feature.dungeon.gui;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DungeonMapLayoutPlannerTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ > ### 8.2 現在ダンジョンマップ
     * 検証契約: 複数の通路が同じマスを通るときも、分岐する辺と各接続を保持する。
     */
    @Test
    void preservesBranchAtSharedCorridorSlot() {
        var planner = new DungeonMapLayoutPlanner();
        var layout = new DungeonLayout(1L, 112, 112, 64, 8,
                List.of(room(0), room(1), room(2)),
                List.of(new DungeonLayout.Connection(0, 0, 1, List.of()),
                        new DungeonLayout.Connection(1, 0, 2, List.of())),
                0, 2);
        var placements = List.of(new DungeonMapLayoutPlanner.Placement(0, 0, 9),
                new DungeonMapLayoutPlanner.Placement(1, 0, 13),
                new DungeonMapLayoutPlanner.Placement(2, 0, 30));

        var junction = planner.planCorridors(layout, placements).stream()
                .filter(corridor -> corridor.slot() == 12)
                .findFirst().orElseThrow();

        assertEquals(Map.of(0, 2 | 8, 1, 4 | 8), junction.directionsByConnection());
        assertEquals(2 | 4 | 8, junction.directionMask());
    }

    private static DungeonLayout.Room room(int id) {
        return new DungeonLayout.Room(id, new DungeonLayout.Rect(id, id, id + 1, id + 1),
                DungeonRoomShape.RECTANGLE, DungeonLayout.RoomRole.NORMAL, id);
    }
}
