package io.github.maaasu.astralRecord.feature.dungeon.view;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonMapRoomState;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import org.jetbrains.annotations.NotNull;

/** 部屋内 TextDisplay に表示する役割と攻略状態を構築します。 */
public final class DungeonRoomStatusText {
    /** インスタンス化を禁止します。 */
    private DungeonRoomStatusText() {
    }

    /**
     * プレイヤー向け区画番号・役割と現在状態の2行表示を構築します。
     *
     * @param layout 表示番号を決める確定済み配置
     * @param room 表示対象の部屋
     * @param state 現在の進行状態
     * @return 部屋を一意に示す見出しと現在状態の2行表示
     */
    public static @NotNull String render(
            @NotNull DungeonLayout layout,
            @NotNull DungeonLayout.Room room,
            @NotNull DungeonMapRoomState state
    ) {
        String role = PlayerMsgResource.getMessage(roleMessage(room.role()).getId());
        String heading = PlayerMsgResource.format(
                PlayerMsgId.P_7076.getId(),
                displayNumber(layout, room.id()),
                role
        );
        String status = PlayerMsgResource.getMessage(statusMessage(room.role(), state).getId());
        return heading + "\n" + status;
    }

    /**
     * 内部IDを公開せず、配置順に1から始まる表示番号を返します。
     *
     * @param layout 確定済み配置
     * @param roomId 表示対象の内部部屋ID
     * @return プレイヤー向け区画番号
     * @throws IllegalArgumentException 部屋IDが配置に存在しない場合
     */
    private static int displayNumber(@NotNull DungeonLayout layout, int roomId) {
        for (int index = 0; index < layout.rooms().size(); index++) {
            if (layout.rooms().get(index).id() == roomId) {
                return index + 1;
            }
        }
        throw new IllegalArgumentException("Room is not part of layout: " + roomId);
    }

    /**
     * @param role 部屋の進行上の役割
     * @return 役割表示に使うメッセージID
     */
    private static @NotNull PlayerMsgId roleMessage(@NotNull DungeonLayout.RoomRole role) {
        return switch (role) {
            case START -> PlayerMsgId.P_7054;
            case NORMAL -> PlayerMsgId.P_7055;
            case BOSS -> PlayerMsgId.P_7056;
        };
    }

    /**
     * STARTを常に安全地帯とし、それ以外は進行状態に対応するメッセージIDを返します。
     *
     * @param role 部屋の進行上の役割
     * @param state 現在の進行状態
     * @return 状態表示に使うメッセージID
     */
    private static @NotNull PlayerMsgId statusMessage(
            @NotNull DungeonLayout.RoomRole role,
            @NotNull DungeonMapRoomState state
    ) {
        if (role == DungeonLayout.RoomRole.START) {
            return PlayerMsgId.P_7077;
        }
        return switch (state) {
            case LOCKED -> PlayerMsgId.P_7078;
            case AVAILABLE -> PlayerMsgId.P_7079;
            case ACTIVE -> PlayerMsgId.P_7051;
            case CLEARED -> PlayerMsgId.P_7052;
        };
    }
}
