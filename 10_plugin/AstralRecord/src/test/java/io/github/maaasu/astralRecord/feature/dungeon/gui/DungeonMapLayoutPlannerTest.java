package io.github.maaasu.astralRecord.feature.dungeon.gui;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonMapLayoutPlannerTest {
    private final DungeonMapLayoutPlanner planner = new DungeonMapLayoutPlanner();

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ
     * 検証契約: 同じ部屋配置は相対座標を保つ同じpage/slotへ決定的に射影する。
     */
    @Test
    void projectsRelativeRoomPositionsDeterministically() {
        DungeonLayout layout = layout(List.of(
                room(0, 0, 0),
                room(1, 50, 0),
                room(2, 0, 50),
                room(3, 50, 50)
        ));

        List<DungeonMapLayoutPlanner.Placement> first = planner.plan(layout);
        List<DungeonMapLayoutPlanner.Placement> second = planner.plan(layout);

        assertEquals(first, second);
        assertEquals(0, first.get(0).slot());
        assertEquals(8, first.get(1).slot());
        assertEquals(36, first.get(2).slot());
        assertEquals(44, first.get(3).slot());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ
     * 検証契約: 同一slotへ射影された部屋は決定的な最寄り空きslotへ退避し、45室超もページ分割して欠落させない。
     */
    @Test
    void resolvesCollisionsAndPaginatesWithoutDroppingRooms() {
        List<DungeonLayout.Room> rooms = new ArrayList<>();
        for (int id = 0; id < 50; id++) {
            rooms.add(room(id, 10, 10));
        }

        List<DungeonMapLayoutPlanner.Placement> placements = planner.plan(layout(rooms));
        Set<String> occupied = new HashSet<>();
        placements.forEach(placement -> occupied.add(placement.page() + ":" + placement.slot()));

        assertEquals(50, placements.size());
        assertEquals(50, occupied.size());
        assertEquals(45L, placements.stream().filter(placement -> placement.page() == 0).count());
        assertEquals(5L, placements.stream().filter(placement -> placement.page() == 1).count());
        assertTrue(placements.stream().allMatch(placement ->
                placement.slot() >= 0 && placement.slot() < DungeonMapLayoutPlanner.PAGE_SIZE));
    }

    private DungeonLayout layout(List<DungeonLayout.Room> rooms) {
        return new DungeonLayout(123L, 128, 128, 64, 8, rooms, List.of(), 0, rooms.getLast().id());
    }

    private DungeonLayout.Room room(int id, int x, int z) {
        return new DungeonLayout.Room(
                id,
                new DungeonLayout.Rect(x, z, x + 8, z + 8),
                DungeonRoomShape.RECTANGLE,
                DungeonRoomType.STANDARD,
                id == 0 ? DungeonLayout.RoomRole.START : DungeonLayout.RoomRole.NORMAL,
                id
        );
    }
}
