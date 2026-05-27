package io.github.maaasu.astralRecord.feature.combat.model;

/**
 * 攻撃種別を表します。
 * <p>
 * 武器カテゴリ・スキル種別・Mob 攻撃などの発生経路に依らず、
 * ダメージ計算で参照する攻撃力の種別を識別するために使用します。
 *
 * @see io.github.maaasu.astralRecord.feature.status.model.StatusType#MELEE_ATTACK
 * @see io.github.maaasu.astralRecord.feature.status.model.StatusType#RANGED_ATTACK
 * @see io.github.maaasu.astralRecord.feature.status.model.StatusType#MAGIC_ATTACK
 */
public enum AttackType {

    /** 近接攻撃。STRENGTH スケーリング。 */
    MELEE,

    /** 間接攻撃（弓・投擲等）。DEXTERITY スケーリング。 */
    RANGED,

    /** 魔法攻撃。INTELLIGENCE スケーリング。 */
    MAGIC;
}
