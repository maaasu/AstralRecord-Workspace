package io.github.maaasu.astralRecord.feature.mob.model;

/**
 * Mob の組み込み通常攻撃設定です。
 *
 * @param range         直接通常攻撃を試行する最大距離（ブロック単位）
 * @param intervalTicks 通常攻撃の基本間隔（tick）
 */
public record MobNormalAttackConfig(double range, long intervalTicks) {

    /** 不正な距離・間隔を安全な最小値へ補正します。 */
    public MobNormalAttackConfig {
        range = Math.max(0.0D, range);
        intervalTicks = Math.max(1L, intervalTicks);
    }
}
