package io.github.maaasu.astralRecord.feature.boss.model;

/**
 * Scaling settings applied from participant count.
 */
public record BossScalingConfig(
        boolean enabled,
        double healthPerExtraPlayer,
        double attackPerExtraPlayer
) {
    public static final BossScalingConfig EMPTY = new BossScalingConfig(false, 0.0D, 0.0D);
}
