package io.github.maaasu.astralRecord.feature.combat.model;

/**
 * ダメージの発生元を表します。
 */
public enum DamageSource {

    /** 武器による通常攻撃です。 */
    NORMAL_ATTACK,

    /** 発動スキルによる攻撃です。 */
    SKILL,

    /** 環境・固定値など、攻撃ステータス補正の対象外となるダメージです。 */
    OTHER
}
