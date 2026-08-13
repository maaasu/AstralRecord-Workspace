package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonMapRoomState;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonStartRoomProgressionTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 4. 開始・生成・転送
     * 検証契約: 開始カウント完了時はSTARTをACTIVEにせず直接CLEAREDとし、距離1のNORMAL部屋をAVAILABLEにする。
     */
    @Test
    void completesSafeStartAndUnlocksFirstNormalRoomsWithoutAnEncounter() {
        DungeonLayout layout = layout();
        Map<Integer, DungeonMapRoomState> states = new LinkedHashMap<>(Map.of(
                0, DungeonMapRoomState.AVAILABLE,
                1, DungeonMapRoomState.LOCKED,
                2, DungeonMapRoomState.LOCKED,
                3, DungeonMapRoomState.LOCKED
        ));

        DungeonStartRoomProgression.Transition transition =
                DungeonStartRoomProgression.complete(layout, states);

        assertTrue(transition.completed());
        assertEquals(List.of(10, 11), transition.connectionsToOpen().stream()
                .map(DungeonLayout.Connection::id)
                .toList());
        assertEquals(DungeonMapRoomState.CLEARED, states.get(0));
        assertEquals(DungeonMapRoomState.AVAILABLE, states.get(1));
        assertEquals(DungeonMapRoomState.AVAILABLE, states.get(2));
        assertEquals(DungeonMapRoomState.LOCKED, states.get(3));
        assertFalse(DungeonRoomEncounterPolicy.hasEncounter(layout.rooms().getFirst()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 4. 開始・生成・転送
     * 検証契約: START完了処理は一度だけ状態とゲート解放を変更する。
     */
    @Test
    void completionIsIdempotent() {
        DungeonLayout layout = layout();
        Map<Integer, DungeonMapRoomState> states = new LinkedHashMap<>(Map.of(
                0, DungeonMapRoomState.AVAILABLE,
                1, DungeonMapRoomState.LOCKED,
                2, DungeonMapRoomState.LOCKED,
                3, DungeonMapRoomState.LOCKED
        ));

        assertTrue(DungeonStartRoomProgression.complete(layout, states).completed());
        DungeonStartRoomProgression.Transition second = DungeonStartRoomProgression.complete(layout, states);

        assertFalse(second.completed());
        assertTrue(second.connectionsToOpen().isEmpty());
    }

    private DungeonLayout layout() {
        return new DungeonLayout(
                1L,
                64,
                64,
                64,
                8,
                List.of(
                        room(0, DungeonLayout.RoomRole.START, 0),
                        room(1, DungeonLayout.RoomRole.NORMAL, 1),
                        room(2, DungeonLayout.RoomRole.NORMAL, 1),
                        room(3, DungeonLayout.RoomRole.BOSS, 2)
                ),
                List.of(
                        new DungeonLayout.Connection(10, 0, 1, List.of()),
                        new DungeonLayout.Connection(11, 0, 2, List.of()),
                        new DungeonLayout.Connection(12, 1, 3, List.of())
                ),
                0,
                3
        );
    }

    private DungeonLayout.Room room(int id, DungeonLayout.RoomRole role, int distanceFromStart) {
        return new DungeonLayout.Room(
                id,
                new DungeonLayout.Rect(id * 10, 0, id * 10 + 8, 8),
                DungeonRoomShape.RECTANGLE,
                role,
                distanceFromStart
        );
    }
}
