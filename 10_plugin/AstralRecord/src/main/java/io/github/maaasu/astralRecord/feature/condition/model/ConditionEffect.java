package io.github.maaasu.astralRecord.feature.condition.model;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageType;
import org.jetbrains.annotations.NotNull;

/**
 * 状態異常が持つ具体的な効果値を表します。
 *
 * @param tickIntervalTicks periodic effect の間隔。0 以下なら tick 処理なし
 * @param basePower 固定効果値
 * @param sourceAttackCoefficient 付与元 ATTACK 係数
 * @param sourceTypedAttackCoefficient 付与元攻撃種別ステータス係数
 * @param targetMaxHealthCoefficient 対象最大 HP 係数
 * @param maxTickDamage 1 tick 処理あたりの上限。0 以下なら上限なし
 * @param healingReceivedMultiplier 受ける回復量倍率
 * @param damageTakenMultiplier 受けるダメージ倍率
 * @param movementBlocked 移動不可か
 * @param attackBlocked 通常攻撃不可か
 * @param skillBlocked スキル使用不可か
 * @param aiBlocked Mob AI 停止か
 * @param damageImmune HP/Shield ダメージ無効か
 * @param damageType DoT のダメージ種別
 * @param damageElement DoT または表示用属性
 */
public record ConditionEffect(
        int tickIntervalTicks,
        double basePower,
        double sourceAttackCoefficient,
        double sourceTypedAttackCoefficient,
        double targetMaxHealthCoefficient,
        double maxTickDamage,
        double healingReceivedMultiplier,
        double damageTakenMultiplier,
        boolean movementBlocked,
        boolean attackBlocked,
        boolean skillBlocked,
        boolean aiBlocked,
        boolean damageImmune,
        @NotNull DamageType damageType,
        @NotNull DamageElement damageElement
) {
}
