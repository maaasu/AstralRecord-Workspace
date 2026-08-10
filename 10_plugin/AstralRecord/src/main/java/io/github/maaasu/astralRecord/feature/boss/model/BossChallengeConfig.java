package io.github.maaasu.astralRecord.feature.boss.model;

import org.jetbrains.annotations.NotNull;

/**
 * Challenge settings attached to a BOSS mob template.
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
        @NotNull BossScalingConfig scaling
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
