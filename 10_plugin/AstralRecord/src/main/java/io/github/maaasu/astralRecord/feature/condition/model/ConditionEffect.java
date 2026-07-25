package io.github.maaasu.astralRecord.feature.condition.model;

/**
 * 状態異常の既定効果です。
 *
 * @param tickIntervalTicks DoT間隔。0はDoTなし
 * @param healthRate 1tickあたりのHP割合。0.01は1%
 * @param currentHealthBased trueなら現在HP、falseなら最大HPを基準にする
 * @param basePower 固定DoT値
 * @param sourceAttackCoefficient 付与元攻撃力係数
 * @param movementSpeedMultiplier 移動速度倍率
 * @param castTimeMultiplier 詠唱時間倍率
 * @param damageDealtMultiplier 与える最終ダメージ倍率
 * @param movementBlocked 移動不可
 * @param attackBlocked 通常攻撃不可
 * @param skillBlocked スキル不可
 * @param aiBlocked Mob AI不可
 * @param healingBlocked 回復不可
 * @param controlIntervalMinTicks 間欠行動不能の最短発生間隔
 * @param controlIntervalMaxTicks 間欠行動不能の最長発生間隔
 * @param controlDurationTicks 間欠行動不能の継続時間
 */
public record ConditionEffect(
        int tickIntervalTicks,
        double healthRate,
        boolean currentHealthBased,
        double basePower,
        double sourceAttackCoefficient,
        double movementSpeedMultiplier,
        double castTimeMultiplier,
        double damageDealtMultiplier,
        boolean movementBlocked,
        boolean attackBlocked,
        boolean skillBlocked,
        boolean aiBlocked,
        boolean healingBlocked,
        int controlIntervalMinTicks,
        int controlIntervalMaxTicks,
        int controlDurationTicks
) {
}
