package io.github.maaasu.astralRecord.feature.combat.model;

/**
 * ダメージ種別を表します。
 * <p>
 * 物理／魔法／純粋ダメージ（防御無視）の 3 種別を想定しています。
 * 個別の補正・防御計算の分岐に使用します。
 */
public enum DamageType {

    /** 物理ダメージ。近接・間接攻撃由来のダメージで、物理防御で軽減される。 */
    PHYSICAL,

    /** 魔法ダメージ。魔法攻撃由来のダメージで、魔法防御で軽減される。 */
    MAGIC,

    /** 純粋ダメージ。防御計算をスキップし、最終ダメージへ直接適用される。 */
    TRUE;
}
