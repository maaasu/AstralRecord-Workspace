package io.github.maaasu.astralRecord.feature.boss.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

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
 */
public record BossChallengeSidebarInfo(
        @NotNull String bossDisplayName,
        int bossLevel,
        int deathCount,
        int deathLimit,
        long elapsedSeconds,
        long timeLimitSeconds,
        @NotNull List<String> participantNames
) {
    public BossChallengeSidebarInfo {
        bossLevel = Math.max(0, bossLevel);
        participantNames = List.copyOf(participantNames);
    }
}
