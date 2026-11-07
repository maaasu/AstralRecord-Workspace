package io.github.maaasu.astralRecord.feature.boss.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * ボスマスタの挑戦設定です。
 * @param recommendedPlayerLevel 攻略目安のプレイヤーレベル。未設定の場合は null
 */
public record BossChallengeConfig(
        @NotNull String fieldWorldId,
        @NotNull BossLocation entryLocation,
        double entryRadius,
        @NotNull BossLocation playerSpawnLocation,
        @NotNull BossLocation bossSpawnLocation,
        int partyMin,
        int partyMax,
        long timeLimitSeconds,
        int deathLimit,
        long reviveDelaySeconds,
        @NotNull BossScalingConfig scaling,
        @Nullable Integer recommendedPlayerLevel
) {
    public BossChallengeConfig {
        entryRadius = Math.max(0.5D, entryRadius);
        partyMin = Math.max(1, partyMin);
        partyMax = Math.max(partyMin, partyMax);
        timeLimitSeconds = Math.max(30L, timeLimitSeconds);
        deathLimit = Math.max(0, deathLimit);
        reviveDelaySeconds = Math.max(1L, reviveDelaySeconds);
        if (scaling == null) {
            scaling = BossScalingConfig.EMPTY;
        }
    }
}
