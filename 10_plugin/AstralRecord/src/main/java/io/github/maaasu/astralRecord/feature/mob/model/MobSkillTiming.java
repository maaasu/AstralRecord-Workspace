package io.github.maaasu.astralRecord.feature.mob.model;

/**
 * Mob 専用スキルのAI発動・詠唱・再使用設定です。
 *
 * @param activationRange 発動開始距離（ブロック単位）
 * @param cooldownTicks   再使用間隔（tick）
 * @param castTimeTicks   詠唱時間（tick）
 */
public record MobSkillTiming(double activationRange, long cooldownTicks, long castTimeTicks) {

    /** 不正な値を安全な最小値へ補正します。 */
    public MobSkillTiming {
        activationRange = Math.max(0.0D, activationRange);
        cooldownTicks = Math.max(0L, cooldownTicks);
        castTimeTicks = Math.max(0L, castTimeTicks);
    }
}
