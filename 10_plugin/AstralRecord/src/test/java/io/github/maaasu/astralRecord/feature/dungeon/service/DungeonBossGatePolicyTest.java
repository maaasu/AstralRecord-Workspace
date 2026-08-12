package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DungeonBossGatePolicyTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 3. 遭遇 Mob と部屋進行
     * 検証契約: 通常部屋クリア時は従来どおり、その部屋から通常子部屋へ向かう接続を解放する。
     */
    @Test
    void unlocksOutgoingNormalRoomConnectionsImmediately() {
        DungeonLayout layout = layout(List.of(
                room(0, DungeonLayout.RoomRole.START),
                room(1, DungeonLayout.RoomRole.NORMAL),
                room(2, DungeonLayout.RoomRole.NORMAL),
                room(3, DungeonLayout.RoomRole.BOSS)
        ), List.of(connection(0, 0, 1), connection(1, 0, 2), connection(2, 1, 3)));

        assertEquals(
                List.of(0, 1),
                DungeonBossGatePolicy.connectionsToUnlockAfterClear(layout, 0, Set.of(0)::contains)
                        .stream().map(DungeonLayout.Connection::id).toList()
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 3. 遭遇 Mob と部屋進行
     * 検証契約: 分岐構造ではBOSS親をクリア済みでも別枝に未クリア部屋があればBOSS部屋を解放しない。
     */
    @Test
    void keepsBossRoomLockedWhileAnotherBranchRemainsUncleared() {
        DungeonLayout layout = layout(List.of(
                room(0, DungeonLayout.RoomRole.START),
                room(1, DungeonLayout.RoomRole.NORMAL),
                room(2, DungeonLayout.RoomRole.NORMAL),
                room(3, DungeonLayout.RoomRole.BOSS)
        ), List.of(connection(0, 0, 1), connection(1, 0, 2), connection(2, 1, 3)));

        assertEquals(
                List.of(),
                DungeonBossGatePolicy.connectionsToUnlockAfterClear(layout, 1, Set.of(0, 1)::contains)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 3. 遭遇 Mob と部屋進行
     * 検証契約: 分岐順に依存せず最後の通常部屋をクリアした時点でBOSS部屋を解放できる。
     */
    @Test
    void unlocksBossRoomWhenLastUnclearedBranchIsCleared() {
        DungeonLayout layout = layout(List.of(
                room(0, DungeonLayout.RoomRole.START),
                room(1, DungeonLayout.RoomRole.NORMAL),
                room(2, DungeonLayout.RoomRole.NORMAL),
                room(3, DungeonLayout.RoomRole.BOSS)
        ), List.of(connection(0, 0, 1), connection(1, 0, 2), connection(2, 1, 3)));

        assertEquals(
                List.of(2),
                DungeonBossGatePolicy.connectionsToUnlockAfterClear(layout, 2, Set.of(0, 1, 2)::contains)
                        .stream().map(DungeonLayout.Connection::id).toList()
        );
    }

    private DungeonLayout layout(
            List<DungeonLayout.Room> rooms,
            List<DungeonLayout.Connection> connections
    ) {
        return new DungeonLayout(1L, 64, 64, 64, 4, rooms, connections, 0, rooms.size() - 1);
    }

    private DungeonLayout.Room room(int id, DungeonLayout.RoomRole role) {
        return new DungeonLayout.Room(
                id,
                new DungeonLayout.Rect(id * 10, 0, id * 10 + 6, 6),
                DungeonRoomShape.RECTANGLE,
                role,
                id
        );
    }

    private DungeonLayout.Connection connection(int id, int fromRoomId, int toRoomId) {
        return new DungeonLayout.Connection(id, fromRoomId, toRoomId, List.of());
    }
}
