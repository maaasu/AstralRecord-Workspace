package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import org.jetbrains.annotations.NotNull;

/** ダンジョン部屋の役割から遭遇戦の有無と初戦判定を決める純粋な方針です。 */
public final class DungeonRoomEncounterPolicy {
    /** インスタンス化を禁止します。 */
    private DungeonRoomEncounterPolicy() {
    }

    /**
     * STARTを除く戦闘部屋かを判定します。
     *
     * @param room 判定対象の部屋
     * @return この部屋でMob遭遇戦を開始する場合は {@code true}
     */
    public static boolean hasEncounter(@NotNull DungeonLayout.Room room) {
        return room.role() != DungeonLayout.RoomRole.START;
    }

    /**
     * 最初の通常戦闘部屋へ初戦レベル上限を適用するか判定します。
     *
     * @param room 判定対象の部屋
     * @return START直後の通常戦闘部屋の場合は {@code true}
     */
    public static boolean isFirstCombatRoom(@NotNull DungeonLayout.Room room) {
        return room.role() == DungeonLayout.RoomRole.NORMAL && room.distanceFromStart() == 1;
    }
}
