package io.github.maaasu.astralRecord.feature.combat.model;

/**
 * ダメージ算出時の基礎ダメージ解決方式です。
 */
public enum DamageScaling {

    /** 攻撃者のステータスからダメージを組み立てます。 */
    ATTACKER_STATUS,

    /** 外部から渡された基礎ダメージをそのまま使います。 */
    FIXED
}
