package io.github.maaasu.astralRecord.feature.mob.model;

/**
 * Mob のターゲット選定設定。{@code NPC} カテゴリでは {@code null} となる。
 *
 * @param strategy     ターゲット選定方式
 * @param aggroRange   敵対検知範囲（ブロック単位）
 * @param deaggroRange 敵対解除距離（ブロック単位）
 * @param leashRange   スポーン地点からの最大追跡距離
 */
public record MobTargetingConfig(
        TargetStrategy strategy,
        double aggroRange,
        double deaggroRange,
        double leashRange
) {
}
