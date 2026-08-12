package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/** ボス部屋を最後の戦闘に固定するため、通常部屋全体の進行状態を判定します。 */
final class DungeonBossGatePolicy {
    private DungeonBossGatePolicy() {
    }

    /**
     * クリア部屋から通常子部屋へ出る接続と、全通常部屋クリア後の BOSS 接続を返します。
     *
     * @param layout 判定対象のダンジョン配置
     * @param clearedRoomId 今回クリアした部屋 ID
     * @param roomCleared 部屋 ID ごとのクリア済み判定
     * @return 今回解放する接続。BOSS 接続は BOSS 以外の全部屋がクリア済みの場合だけ含む
     */
    static @NotNull List<DungeonLayout.Connection> connectionsToUnlockAfterClear(
            @NotNull DungeonLayout layout,
            int clearedRoomId,
            @NotNull IntPredicate roomCleared
    ) {
        List<DungeonLayout.Connection> result = new ArrayList<>();
        for (DungeonLayout.Connection connection : layout.connections()) {
            if (connection.fromRoomId() == clearedRoomId
                    && room(layout, connection.toRoomId()).role() != DungeonLayout.RoomRole.BOSS) {
                result.add(connection);
            }
        }
        boolean allNonBossRoomsCleared = layout.rooms().stream()
                .filter(room -> room.role() != DungeonLayout.RoomRole.BOSS)
                .allMatch(room -> roomCleared.test(room.id()));
        if (allNonBossRoomsCleared) {
            for (DungeonLayout.Connection connection : layout.connections()) {
                if (connection.toRoomId() == layout.bossRoomId()) {
                    result.add(connection);
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * 配置から部屋 ID に一致する部屋を取得します。
     *
     * @param layout 判定対象のダンジョン配置
     * @param roomId 取得する部屋 ID
     * @return 一致する部屋
     * @throws java.util.NoSuchElementException 配置に部屋 ID が存在しない場合
     */
    private static @NotNull DungeonLayout.Room room(@NotNull DungeonLayout layout, int roomId) {
        return layout.rooms().stream()
                .filter(room -> room.id() == roomId)
                .findFirst()
                .orElseThrow();
    }
}
