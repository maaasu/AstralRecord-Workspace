package io.github.maaasu.astralRecord.feature.combat.model;

/**
 * 超星会心倍率の適用方法を表します。
 */
public enum SuperStarCriticalMode {
    /** 発生率で判定し、成立時は設定率を100%へ加算して主攻撃へ適用します。 */
    ROLL,
    /** 発生率を判定せず、設定率だけを追尾弾の倍率として適用します。 */
    FORCE,
    /** 発生率と倍率を参照せず、超星会心を無効化します。 */
    DISABLED
}
