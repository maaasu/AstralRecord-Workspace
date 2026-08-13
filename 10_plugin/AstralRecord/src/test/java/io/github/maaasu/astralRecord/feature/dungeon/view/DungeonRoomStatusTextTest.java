package io.github.maaasu.astralRecord.feature.dungeon.view;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonMapRoomState;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRoomStatusTextTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 3. 遭遇 Mob と部屋進行
     * 検証契約: 部屋内表示だけで部屋番号、役割、未開放・進入可能・攻略中・攻略済みを判別できる。
     */
    @Test
    void rendersEveryNormalRoomStateInsideTheDungeon() {
        DungeonLayout.Room start = room(100, DungeonLayout.RoomRole.START, 0);
        DungeonLayout.Room room = room(4, DungeonLayout.RoomRole.NORMAL, 2);
        DungeonLayout.Room boss = room(80, DungeonLayout.RoomRole.BOSS, 4);
        DungeonLayout layout = layout(List.of(start, room, boss), start.id(), boss.id());

        assertContains(DungeonRoomStatusText.render(layout, room, DungeonMapRoomState.LOCKED), "区画 2", "通常区画", "未開放");
        assertContains(DungeonRoomStatusText.render(layout, room, DungeonMapRoomState.AVAILABLE), "進入可能");
        assertContains(DungeonRoomStatusText.render(layout, room, DungeonMapRoomState.ACTIVE), "攻略中の部屋");
        assertContains(DungeonRoomStatusText.render(layout, room, DungeonMapRoomState.CLEARED), "攻略済みの部屋");
        assertContains(
                DungeonRoomStatusText.render(layout, boss, DungeonMapRoomState.AVAILABLE),
                "区画 3",
                "最深部",
                "進入可能"
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 3. 遭遇 Mob と部屋進行
     * 検証契約: START部屋は進行用のCLEARED状態になっても、安全地帯として表示し続ける。
     */
    @Test
    void startRoomAlwaysRendersAsSafeArea() {
        DungeonLayout.Room room = room(0, DungeonLayout.RoomRole.START, 0);
        DungeonLayout layout = layout(List.of(room), room.id(), room.id());

        assertContains(
                DungeonRoomStatusText.render(layout, room, DungeonMapRoomState.CLEARED),
                "区画 1",
                "入口",
                "安全地帯"
        );
    }

    private void assertContains(String actual, String... expectedParts) {
        for (String expected : expectedParts) {
            assertTrue(actual.contains(expected), () -> "Expected '" + expected + "' in: " + actual);
        }
    }

    private DungeonLayout.Room room(int id, DungeonLayout.RoomRole role, int distanceFromStart) {
        return new DungeonLayout.Room(
                id,
                new DungeonLayout.Rect(0, 0, 8, 8),
                DungeonRoomShape.RECTANGLE,
                role,
                distanceFromStart
        );
    }

    private DungeonLayout layout(List<DungeonLayout.Room> rooms, int startRoomId, int bossRoomId) {
        return new DungeonLayout(1L, 64, 64, 64, 8, rooms, List.of(), startRoomId, bossRoomId);
    }
}
