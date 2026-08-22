package io.github.maaasu.astralRecord.feature.boss.model;

import io.github.maaasu.astralRecord.shared.challenge.ChallengeWaitingStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * プレイヤー向けサイドバーに表示するボス挑戦情報です。
 *
 * @param bossDisplayName ボス表示名
 * @param bossLevel ボスレベル
 * @param deathCount パーティー共有の現在死亡回数
 * @param deathLimit パーティー共有の死亡上限
 * @param elapsedSeconds 挑戦開始からの経過秒数
 * @param timeLimitSeconds 挑戦制限時間
 * @param participantNames 挑戦参加者の表示名
 * @param waitingStatus 準備中の待機状態
 * @param waitingParticipantNames ハブへ未到着で灰色表示する参加者名
 */
public record BossChallengeSidebarInfo(
        @NotNull String bossDisplayName,
        int bossLevel,
        int deathCount,
        int deathLimit,
        long elapsedSeconds,
        long timeLimitSeconds,
        @NotNull List<String> participantNames,
        @NotNull ChallengeWaitingStatus waitingStatus,
        @NotNull Set<String> waitingParticipantNames
) {
    /**
     * 従来の戦闘中表示を作成します。
     *
     * @param bossDisplayName ボス表示名
     * @param bossLevel ボスレベル
     * @param deathCount 現在死亡回数
     * @param deathLimit 死亡上限
     * @param elapsedSeconds 経過秒数
     * @param timeLimitSeconds 制限時間
     * @param participantNames 参加者名
     */
    public BossChallengeSidebarInfo(
            @NotNull String bossDisplayName,
            int bossLevel,
            int deathCount,
            int deathLimit,
            long elapsedSeconds,
            long timeLimitSeconds,
            @NotNull List<String> participantNames
    ) {
        this(
                bossDisplayName,
                bossLevel,
                deathCount,
                deathLimit,
                elapsedSeconds,
                timeLimitSeconds,
                participantNames,
                ChallengeWaitingStatus.NONE,
                Set.of()
        );
    }

    public BossChallengeSidebarInfo {
        bossLevel = Math.max(0, bossLevel);
        participantNames = List.copyOf(participantNames);
        waitingStatus = waitingStatus == null ? ChallengeWaitingStatus.NONE : waitingStatus;
        waitingParticipantNames = Set.copyOf(waitingParticipantNames);
    }

    /**
     * サイドバーで予約する行数を返します。
     *
     * @return ボス情報の表示行数
     */
    public int sidebarLineCount() {
        return 5 + (waitingStatus.isVisible() ? 1 : 0);
    }
}
