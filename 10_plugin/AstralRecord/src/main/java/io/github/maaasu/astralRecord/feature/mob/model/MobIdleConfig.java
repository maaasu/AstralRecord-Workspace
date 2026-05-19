package io.github.maaasu.astralRecord.feature.mob.model;

/**
 * Mobの待機行動設定。
 *
 * @param behavior     行動パターン
 * @param wanderRadius 徘徊半径
 * @param speed        移動速度倍率
 */
public record MobIdleConfig(String behavior, double wanderRadius, double speed) {
}
