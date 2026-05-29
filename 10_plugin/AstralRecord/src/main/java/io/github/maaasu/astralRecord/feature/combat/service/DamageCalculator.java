package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.DamageContext;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.jetbrains.annotations.NotNull;

/**
 * ダメージ計算の純粋ロジックを担うクラスです。
 * <p>
 * Bukkit API への依存を持たず、{@link DamageContext} の入力値のみから
 * {@link DamageResult} を導出します。攻撃力／防御力／会心／属性補正等は
 * 将来この実装内で拡張する想定で、現状は下限クランプのみを行います。
 */
public final class DamageCalculator {

    /**
     * ダメージを計算し、結果を返します。
     * <p>
     * 攻撃力・防御力・クリティカル・属性補正などはここで計算します。
     * 最終ダメージは必ず 0 以上にクランプします。
     *
     * @param context ダメージ計算入力
     * @return 計算結果
     */
    public @NotNull DamageResult calculate(@NotNull DamageContext context) {
        double damage = resolveBaseDamage(context);

        if (context.damageType() != DamageType.TRUE && context.victim().isManaged()) {
            double defense = defensePower(context);
            damage = Math.max(1.0D, damage - defense * 0.5D);
        }

        damage = Math.max(0.0D, damage);

        return new DamageResult(damage);
    }

    private double resolveBaseDamage(@NotNull DamageContext context) {
        if (context.scaling() == io.github.maaasu.astralRecord.feature.combat.model.DamageScaling.FIXED) {
            return context.baseDamage();
        }
        if (context.attacker() != null && context.attacker().isManaged()) {
            return Math.max(context.baseDamage(), attackPower(context));
        }
        return context.baseDamage();
    }

    private double attackPower(@NotNull DamageContext context) {
        double attack = context.attacker().statValue(StatusType.ATTACK);
        double typedAttack = context.attacker().statValue(attackStatusType(context));
        double primary = context.attacker().statValue(primaryStatusType(context));
        return attack * (1.0D + primary / 100.0D) + typedAttack;
    }

    private double defensePower(@NotNull DamageContext context) {
        return switch (context.damageType()) {
            case PHYSICAL -> context.victim().statValue(StatusType.DEFENSE);
            case MAGIC -> context.victim().statValue(StatusType.MAGIC_DEFENSE);
            case TRUE -> 0.0D;
        };
    }

    private @NotNull StatusType attackStatusType(@NotNull DamageContext context) {
        return switch (context.attackType()) {
            case MELEE -> StatusType.MELEE_ATTACK;
            case RANGED -> StatusType.RANGED_ATTACK;
            case MAGIC -> StatusType.MAGIC_ATTACK;
        };
    }

    private @NotNull StatusType primaryStatusType(@NotNull DamageContext context) {
        return switch (context.attackType()) {
            case MELEE -> StatusType.STRENGTH;
            case RANGED -> StatusType.DEXTERITY;
            case MAGIC -> StatusType.INTELLIGENCE;
        };
    }
}
