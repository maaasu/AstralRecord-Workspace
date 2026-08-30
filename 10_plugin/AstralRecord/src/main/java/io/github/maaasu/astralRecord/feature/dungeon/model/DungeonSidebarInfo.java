package io.github.maaasu.astralRecord.feature.dungeon.model;

import io.github.maaasu.astralRecord.shared.challenge.ChallengeWaitingStatus;
import io.github.maaasu.astralRecord.shared.challenge.ParticipantNameLineFormatter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * ダンジョン挑戦中の Sidebar 表示スナップショットです。
 *
 * @param dungeonDisplayName ダンジョン表示名
 * @param deathCount パーティー共有死亡回数
 * @param deathLimit 死亡許容回数
 * @param clearedRooms クリア済み部屋数
 * @param totalRooms 全部屋数
 * @param elapsedSeconds 攻略開始後の経過秒数
 * @param timeLimitSeconds 挑戦制限時間。{@code null} は無制限
 * @param participantNames 攻略中は現在インスタンス内、待機中は予定参加者の表示名
 * @param returnRemainingSeconds クリア後の強制帰還までの秒数。進行中は {@code -1}
 * @param waitingStatus 準備中の待機状態
 * @param waitingParticipantNames ハブへ未到着で灰色表示する参加者名
 */
public record DungeonSidebarInfo(
        @NotNull String dungeonDisplayName,
        int deathCount,
        int deathLimit,
        int clearedRooms,
        int totalRooms,
        long elapsedSeconds,
        @Nullable Long timeLimitSeconds,
        @NotNull List<String> participantNames,
        long returnRemainingSeconds,
        @NotNull ChallengeWaitingStatus waitingStatus,
        @NotNull Set<String> waitingParticipantNames
) {
    /**
     * 従来の攻略中表示を作成します。
     *
     * @param dungeonDisplayName ダンジョン表示名
     * @param deathCount 現在死亡回数
     * @param deathLimit 死亡上限
     * @param clearedRooms クリア済み部屋数
     * @param totalRooms 全部屋数
     * @param participantNames 参加者名
     * @param returnRemainingSeconds 強制帰還までの秒数
     */
    public DungeonSidebarInfo(
            @NotNull String dungeonDisplayName,
            int deathCount,
            int deathLimit,
            int clearedRooms,
            int totalRooms,
            @NotNull List<String> participantNames,
            long returnRemainingSeconds
    ) {
        this(
                dungeonDisplayName,
                deathCount,
                deathLimit,
                clearedRooms,
                totalRooms,
                0L,
                null,
                participantNames,
                returnRemainingSeconds,
                ChallengeWaitingStatus.NONE,
                Set.of()
        );
    }

    public DungeonSidebarInfo {
        participantNames = List.copyOf(participantNames);
        waitingStatus = waitingStatus == null ? ChallengeWaitingStatus.NONE : waitingStatus;
        waitingParticipantNames = Set.copyOf(waitingParticipantNames);
    }

    /**
     * サイドバーで予約する行数を返します。
     *
     * @return ダンジョン情報の表示行数
     */
    public int sidebarLineCount() {
        return 5
                + (timeLimitSeconds != null ? 1 : 0)
                + (waitingStatus.isVisible() ? 1 : 0)
                + ParticipantNameLineFormatter.sidebarLineCount(participantNames);
    }

    /**
     * 任意の進行情報を除き、参加者表示を維持するために必要な行数を返します。
     *
     * @return ダンジョン名と参加者の表示行数
     */
    public int requiredSidebarLineCount() {
        return 1 + ParticipantNameLineFormatter.sidebarLineCount(participantNames);
    }
}
