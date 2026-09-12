package io.github.maaasu.astralRecord.feature.combat.model;

/**
 * ダメージ算出時の基礎ダメージ解決方式です。
 */
public enum DamageScaling {

    /** 攻撃者のステータスからダメージを組み立てます。 */
    ATTACKER_STATUS,

    /** 外部で解決した攻撃力参照値を使い、通常の命中・防御・会心計算へ渡します。 */
    EXTERNAL_ATTACK_POWER,

    /** 外部から渡された基礎ダメージをそのまま使います。 */
    FIXED
}
