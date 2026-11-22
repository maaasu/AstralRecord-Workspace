package io.github.maaasu.astralRecord.feature.combat.model;

import io.github.maaasu.astralRecord.feature.status.model.StatusType;

/**
 * ダメージの発生元を表します。
 */
public enum DamageSource {

    /** 武器による通常攻撃です。 */
    NORMAL_ATTACK,

    /** 発動スキルによる攻撃です。 */
    SKILL,

    /** 環境・固定値など、攻撃ステータス補正の対象外となるダメージです。 */
    OTHER;

    /** この発生元にだけ加算する会心率の相対増加。共通補正のみの場合は null。 */
    public StatusType criticalRateIncrease() {
        return switch (this) {
            case NORMAL_ATTACK -> StatusType.NORMAL_ATTACK_CRITICAL_RATE_RELATIVE_INCREASE;
            case SKILL -> StatusType.SKILL_CRITICAL_RATE_RELATIVE_INCREASE;
            case OTHER -> null;
        };
    }
}
