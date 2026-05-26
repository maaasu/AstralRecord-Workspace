package io.github.maaasu.astralRecord.feature.mob.model;

/**
 * Mob の待機行動設定。
 *
 * @param behavior     行動パターン
 * @param wanderRadius 徘徊半径（WANDER 時に使用）
 * @param speed        待機時の移動速度倍率（1.0 = 通常速度）
 */
public record MobIdleConfig(IdleBehavior behavior, double wanderRadius, double speed) {

    /** デフォルト設定（STATIONARY, 10.0, 1.0）。 */
    public static MobIdleConfig defaults() {
        return new MobIdleConfig(IdleBehavior.STATIONARY, 10.0, 1.0);
    }
}
