package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageContext;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageScaling;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * 近接・間接・魔法の攻撃力と属性成分からダメージを算出します。
 */
public final class DamageCalculator {

    private static final double DEFAULT_ELEMENT_RESISTANCE_CAP = 75.0D;

    private static final double DEFAULT_CRITICAL_DAMAGE = 150.0D;
    private static final double DEFAULT_SUPER_CRITICAL_DAMAGE = 100.0D;
    private static final double DEFAULT_ACCURACY = 100.0D;

    private final DoubleSupplier hitRollSupplier;
    private final DoubleSupplier criticalRollSupplier;

    /** サーバー標準乱数を使う計算器を作成します。 */
    public DamageCalculator() {
        this(
                () -> ThreadLocalRandom.current().nextDouble(0.0D, 100.0D),
                () -> ThreadLocalRandom.current().nextDouble(0.0D, 100.0D)
        );
    }

    DamageCalculator(@NotNull DoubleSupplier criticalRollSupplier) {
        this(() -> 0.0D, criticalRollSupplier);
    }

    DamageCalculator(
            @NotNull DoubleSupplier hitRollSupplier,
            @NotNull DoubleSupplier criticalRollSupplier
    ) {
        this.hitRollSupplier = Objects.requireNonNull(hitRollSupplier, "hitRollSupplier");
        this.criticalRollSupplier = Objects.requireNonNull(criticalRollSupplier, "criticalRollSupplier");
    }

    /**
     * ダメージを計算します。防御は合計ダメージへ一度だけ適用し、その後に各属性成分へ
     * 比例配分して属性増加・耐性・貫通を個別適用します。
     *
     * @param context ダメージ計算入力
     * @return 計算結果
     */
    public @NotNull DamageResult calculate(@NotNull DamageContext context) {
        HitCheck hitCheck = checkHit(context);
        if (!hitCheck.hit()) {
            return DamageResult.evaded(hitCheck.hitChance(), hitCheck.accuracy(), hitCheck.evasion());
        }

        List<DamageComponent> components = context.components().stream()
                .filter(component -> component.ratio() > 0.0D)
                .toList();
        double totalRatio = components.stream().mapToDouble(DamageComponent::ratio).sum();
        if (totalRatio <= 0.0D) {
            return new DamageResult(0.0D, false, hitCheck.hitChance(), hitCheck.accuracy(), hitCheck.evasion());
        }

        double damage = Math.max(0.0D, resolveBaseDamage(context))
                * totalRatio
                * sourceDamageMultiplier(context);
        CriticalDamage criticalDamage = applyCriticalDamage(context, damage);
        damage = criticalDamage.damage();

        if (context.victim().isManaged() && damage > 0.0D) {
            damage = Math.max(1.0D, damage - defensePower(context) * 0.5D);
        }

        double attributedDamage = 0.0D;
        for (DamageComponent component : components) {
            double share = damage * component.ratio() / totalRatio;
            attributedDamage += share * elementMultiplier(context, component.element());
        }

        return new DamageResult(
                Math.max(0.0D, attributedDamage),
                criticalDamage.critical(),
                hitCheck.hitChance(),
                hitCheck.accuracy(),
                hitCheck.evasion()
        );
    }

    private @NotNull HitCheck checkHit(@NotNull DamageContext context) {
        if (context.scaling() != DamageScaling.ATTACKER_STATUS
                || context.attacker() == null
                || !context.attacker().isManaged()) {
            return new HitCheck(true, 100.0D, 100.0D, 0.0D);
        }

        double configuredAccuracy = context.attacker().statValue(StatusType.ACCURACY);
        double accuracy = context.attacker().isMob() && configuredAccuracy <= 0.0D
                ? DEFAULT_ACCURACY
                : Math.max(0.0D, configuredAccuracy);
        double evasion = context.victim().isManaged()
                ? Math.max(0.0D, context.victim().statValue(StatusType.EVASION))
                : 0.0D;
        double hitChance = calculateHitChance(accuracy, evasion);
        return new HitCheck(hitRollSupplier.getAsDouble() < hitChance, hitChance, accuracy, evasion);
    }

    /**
     * 命中率と回避率から最終命中率を算出します。
     *
     * @param accuracy 攻撃者の命中率
     * @param evasion 被弾者の回避率
     * @return 0～100の最終命中率
     */
    public static double calculateHitChance(double accuracy, double evasion) {
        return Math.max(0.0D, Math.min(100.0D, accuracy - evasion));
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
        damage *= (criticalDamage <= 0.0D ? DEFAULT_CRITICAL_DAMAGE : criticalDamage) / 100.0D;

        double superCriticalRate = Math.max(0.0D, context.attacker().statValue(StatusType.SUPER_CRITICAL_RATE));
        if (superCriticalRate > 0.0D && criticalRollSupplier.getAsDouble() < superCriticalRate) {
            double superCriticalDamage = context.attacker().statValue(StatusType.SUPER_CRITICAL_DAMAGE);
            damage *= (superCriticalDamage <= 0.0D ? DEFAULT_SUPER_CRITICAL_DAMAGE : superCriticalDamage) / 100.0D;
        }
        return new CriticalDamage(damage, true);
    }

    private double resolveBaseDamage(@NotNull DamageContext context) {
        if (context.scaling() == DamageScaling.FIXED) {
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
        return calculateDefensePower(context.attacker(), context.victim(), context.attackType());
    }

    /**
     * 全ダメージ防御力と攻撃種別防御力へ対応する貫通率を適用した有効防御力を返します。
     *
     * @param attacker   攻撃者。存在しない場合は防御貫通なし
     * @param victim     被弾者
     * @param attackType 攻撃種別
     * @return 有効防御力
     */
    public static double calculateDefensePower(
            @Nullable io.github.maaasu.astralRecord.feature.combat.model.AstEntity attacker,
            @NotNull io.github.maaasu.astralRecord.feature.combat.model.AstEntity victim,
            @NotNull io.github.maaasu.astralRecord.feature.combat.model.AttackType attackType
    ) {
        double generalDefense = effectiveDefense(
                victim.statValue(StatusType.DEFENSE),
                attacker == null ? 0.0D : attacker.statValue(StatusType.DEFENSE_PENETRATION_RATE)
        );
        double typedDefense = effectiveDefense(
                victim.statValue(defenseStatusType(attackType)),
                attacker == null ? 0.0D : attacker.statValue(defensePenetrationStatusType(attackType))
        );
        return generalDefense + typedDefense;
    }

    private static double effectiveDefense(double defense, double penetrationRate) {
        return Math.max(0.0D, defense)
                * Math.max(0.0D, 1.0D - Math.max(0.0D, penetrationRate) / 100.0D);
    }

    private static @NotNull StatusType defenseStatusType(
            @NotNull io.github.maaasu.astralRecord.feature.combat.model.AttackType attackType
    ) {
        return switch (attackType) {
            case MELEE -> StatusType.MELEE_DEFENSE;
            case RANGED -> StatusType.RANGED_DEFENSE;
            case MAGIC -> StatusType.MAGIC_DEFENSE;
        };
    }

    private static @NotNull StatusType defensePenetrationStatusType(
            @NotNull io.github.maaasu.astralRecord.feature.combat.model.AttackType attackType
    ) {
        return switch (attackType) {
            case MELEE -> StatusType.MELEE_DEFENSE_PENETRATION_RATE;
            case RANGED -> StatusType.RANGED_DEFENSE_PENETRATION_RATE;
            case MAGIC -> StatusType.MAGIC_DEFENSE_PENETRATION_RATE;
        };
    }

    private double sourceDamageMultiplier(@NotNull DamageContext context) {
        if (context.attacker() == null || !context.attacker().isManaged()) {
            return 1.0D;
        }
        StatusType statusType = switch (context.source()) {
            case SKILL -> StatusType.SKILL_DAMAGE_INCREASE;
            case NORMAL_ATTACK -> StatusType.NORMAL_ATTACK_DAMAGE_INCREASE;
            case OTHER -> null;
        };
        if (statusType == null) {
            return 1.0D;
        }
        return Math.max(0.0D, 1.0D + context.attacker().statValue(statusType) / 100.0D);
    }

    private double elementMultiplier(@NotNull DamageContext context, @NotNull DamageElement element) {
        if (element == DamageElement.NONE) {
            return 1.0D;
        }
        double increase = context.attacker() == null ? 0.0D
                : context.attacker().statValue(elementDamageIncrease(element));
        double penetration = context.attacker() == null ? 0.0D
                : context.attacker().statValue(elementPenetration(element));
        double resistance = context.victim().statValue(elementResistance(element));
        double resistanceCap = elementResistanceCap(context, element);
        double effectiveResistance = Math.min(resistance, resistanceCap) - penetration;
        double increaseMultiplier = Math.max(0.0D, 1.0D + increase / 100.0D);
        double resistanceMultiplier = Math.max(0.0D, 1.0D - effectiveResistance / 100.0D);
        return increaseMultiplier * resistanceMultiplier;
    }

    private @Nullable StatusType elementDamageIncrease(@NotNull DamageElement element) {
        return switch (element) {
            case FIRE -> StatusType.FIRE_DAMAGE_INCREASE;
            case ICE -> StatusType.ICE_DAMAGE_INCREASE;
            case LIGHTNING -> StatusType.LIGHTNING_DAMAGE_INCREASE;
            case NONE -> null;
        };
    }

    private @Nullable StatusType elementResistance(@NotNull DamageElement element) {
        return switch (element) {
            case FIRE -> StatusType.FIRE_RESISTANCE;
            case ICE -> StatusType.ICE_RESISTANCE;
            case LIGHTNING -> StatusType.LIGHTNING_RESISTANCE;
            case NONE -> null;
        };
    }

    private @Nullable StatusType elementPenetration(@NotNull DamageElement element) {
        return switch (element) {
            case FIRE -> StatusType.FIRE_PENETRATION;
            case ICE -> StatusType.ICE_PENETRATION;
            case LIGHTNING -> StatusType.LIGHTNING_PENETRATION;
            case NONE -> null;
        };
    }

    private double elementResistanceCap(@NotNull DamageContext context, @NotNull DamageElement element) {
        StatusType capStatus = switch (element) {
            case FIRE -> StatusType.FIRE_RESISTANCE_CAP;
            case ICE -> StatusType.ICE_RESISTANCE_CAP;
            case LIGHTNING -> StatusType.LIGHTNING_RESISTANCE_CAP;
            case NONE -> null;
        };
        if (capStatus == null) {
            return 0.0D;
        }
        if (context.victim().isMob()) {
            return context.victim().mob().template().statValue(capStatus.name(), DEFAULT_ELEMENT_RESISTANCE_CAP);
        }
        return context.victim().statValue(capStatus);
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

    private record CriticalDamage(double damage, boolean critical) {}

    private record HitCheck(boolean hit, double hitChance, double accuracy, double evasion) {}
}
