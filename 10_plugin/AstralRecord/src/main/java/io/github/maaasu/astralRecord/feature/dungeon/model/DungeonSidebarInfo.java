package io.github.maaasu.astralRecord.feature.dungeon.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * ダンジョン挑戦中の Sidebar 表示スナップショットです。
 *
 * @param dungeonDisplayName ダンジョン表示名
 * @param deathCount パーティー共有死亡回数
 * @param deathLimit 死亡許容回数
 * @param clearedRooms クリア済み部屋数
 * @param totalRooms 全部屋数
 * @param participantNames 現在インスタンス内にいる参加者名
 * @param returnRemainingSeconds クリア後の強制帰還までの秒数。進行中は {@code -1}
 */
public record DungeonSidebarInfo(
        @NotNull String dungeonDisplayName,
        int deathCount,
        int deathLimit,
        int clearedRooms,
        int totalRooms,
        @NotNull List<String> participantNames,
        long returnRemainingSeconds
) {
    public DungeonSidebarInfo {
        participantNames = List.copyOf(participantNames);
    }
}
