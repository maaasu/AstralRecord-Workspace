package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.DamageContext;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * ダメージ計算の純粋ロジックを担うクラスです。
 * <p>
 * Bukkit API への依存を持たず、{@link DamageContext} の入力値のみから
 * {@link DamageResult} を導出します。攻撃力／防御力／会心／属性補正等は
 * 将来この実装内で拡張する想定で、現状は下限クランプのみを行います。
 */
public final class DamageCalculator {

    private static final double DEFAULT_CRITICAL_DAMAGE = 150.0D;
    private static final double DEFAULT_SUPER_CRITICAL_DAMAGE = 100.0D;

    private final DoubleSupplier criticalRollSupplier;

    /**
     * サーバー標準の乱数を使うダメージ計算器を作成します。
     */
    public DamageCalculator() {
        this(() -> ThreadLocalRandom.current().nextDouble(0.0D, 100.0D));
    }

    DamageCalculator(@NotNull DoubleSupplier criticalRollSupplier) {
        this.criticalRollSupplier = Objects.requireNonNull(criticalRollSupplier, "criticalRollSupplier");
    }

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
        boolean critical = false;

        CriticalDamage criticalDamage = applyCriticalDamage(context, damage);
        damage = criticalDamage.damage();
        critical = criticalDamage.critical();

        if (context.damageType() != DamageType.TRUE && context.victim().isManaged()) {
            double defense = defensePower(context);
            damage = Math.max(1.0D, damage - defense * 0.5D);
        }

        damage = Math.max(0.0D, damage);

        return new DamageResult(damage, critical);
    }

    private @NotNull CriticalDamage applyCriticalDamage(@NotNull DamageContext context, double damage) {
        if (context.attacker() == null || !context.attacker().isManaged() || damage <= 0.0D) {
            return new CriticalDamage(damage, false);
        }

        double criticalRate = Math.max(0.0D, context.attacker().statValue(StatusType.CRITICAL_RATE));
        if (criticalRate <= 0.0D || criticalRollSupplier.getAsDouble() >= criticalRate) {
            return new CriticalDamage(damage, false);
        }

        double criticalDamage = context.attacker().statValue(StatusType.CRITICAL_DAMAGE);
        if (criticalDamage <= 0.0D) {
            criticalDamage = DEFAULT_CRITICAL_DAMAGE;
        }
        damage *= criticalDamage / 100.0D;

        double superCriticalRate = Math.max(0.0D, context.attacker().statValue(StatusType.SUPER_CRITICAL_RATE));
        if (superCriticalRate > 0.0D && criticalRollSupplier.getAsDouble() < superCriticalRate) {
            double superCriticalDamage = context.attacker().statValue(StatusType.SUPER_CRITICAL_DAMAGE);
            if (superCriticalDamage <= 0.0D) {
                superCriticalDamage = DEFAULT_SUPER_CRITICAL_DAMAGE;
            }
            damage *= superCriticalDamage / 100.0D;
        }
        return new CriticalDamage(damage, true);
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

    private record CriticalDamage(double damage, boolean critical) {
    }
}
