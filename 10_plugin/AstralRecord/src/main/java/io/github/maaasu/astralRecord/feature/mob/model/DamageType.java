package io.github.maaasu.astralRecord.feature.mob.model;

/**
 * Mob の独自ダメージ計算で扱うダメージ種別。
 */
public enum DamageType {

    /** 物理ダメージ。DEFENSE で軽減 */
    PHYSICAL,

    /** 魔法ダメージ。MAGIC_DEFENSE で軽減 */
    MAGIC
}
