package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonMapRoomState;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/** START部屋を戦闘なしで完了し、最初の攻略先を解放する純粋な進行処理です。 */
final class DungeonStartRoomProgression {
    /** インスタンス化を禁止します。 */
    private DungeonStartRoomProgression() {
    }

    /**
     * STARTを直接攻略済みにし、現在条件で解放できる子部屋を進入可能へ更新します。
     *
     * @param layout セッションの確定済み配置
     * @param roomStates 変更対象の部屋状態
     * @return 状態を変更したかと、World上で開く接続
     */
    static @NotNull Transition complete(
            @NotNull DungeonLayout layout,
            @NotNull Map<Integer, DungeonMapRoomState> roomStates
    ) {
        int startRoomId = layout.startRoomId();
        DungeonMapRoomState state = roomStates.get(startRoomId);
        if (state != DungeonMapRoomState.AVAILABLE && state != DungeonMapRoomState.ACTIVE) {
            return new Transition(false, List.of());
        }

        roomStates.put(startRoomId, DungeonMapRoomState.CLEARED);
        List<DungeonLayout.Connection> connections = DungeonBossGatePolicy.connectionsToUnlockAfterClear(
                layout,
                startRoomId,
                roomId -> roomStates.get(roomId) == DungeonMapRoomState.CLEARED
        );
        for (DungeonLayout.Connection connection : connections) {
            if (roomStates.get(connection.toRoomId()) == DungeonMapRoomState.LOCKED) {
                roomStates.put(connection.toRoomId(), DungeonMapRoomState.AVAILABLE);
            }
        }
        return new Transition(true, connections);
    }

    /**
     * @param completed START完了処理を今回適用したか
     * @param connectionsToOpen World上で開く接続
     */
    record Transition(boolean completed, @NotNull List<DungeonLayout.Connection> connectionsToOpen) {
        /** 接続一覧を不変化します。 */
        Transition {
            connectionsToOpen = List.copyOf(connectionsToOpen);
        }
    }
}
