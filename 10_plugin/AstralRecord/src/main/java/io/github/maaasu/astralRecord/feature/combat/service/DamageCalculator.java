package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageBreakdown;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageContext;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageScaling;
import io.github.maaasu.astralRecord.feature.combat.model.SuperStarCriticalMode;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * 近接・間接・魔法の攻撃力と属性成分からダメージを算出します。
 */
public final class DamageCalculator {

    private static final double DEFAULT_ELEMENT_RESISTANCE_CAP = 75.0D;

    private static final double DEFAULT_CRITICAL_DAMAGE = 150.0D;
    private static final double DEFAULT_SUPER_STAR_CRITICAL_MULTIPLIER = 30.0D;
    private static final double DEFAULT_ACCURACY = 100.0D;

    private static final double FULL_BLOCK_DEFENSE_RATIO = 2.5D;
    private static final double EQUAL_POWER_DAMAGE_MULTIPLIER = 0.5D;
    private static final double DEFENSE_CURVE_EXPONENT = Math.log(EQUAL_POWER_DAMAGE_MULTIPLIER)
            / Math.log(1.0D - 1.0D / FULL_BLOCK_DEFENSE_RATIO);

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
     * ダメージを計算します。防御は解決攻撃力との比率から軽減倍率を求め、会心後の合計
     * ダメージへ一度だけ適用します。その後に各属性成分へ比例配分して属性増加・耐性・
     * 貫通を個別適用します。通常会心と超星会心は独立して判定し、通常判定で成立した
     * 超星会心は100%へ設定倍率を加算します。強制適用では設定倍率だけを乗算し、
     * コンテキストで無効化も指定できます。
     *
     * @param context ダメージ計算入力
     * @return 計算結果
     */
    public @NotNull DamageResult calculate(@NotNull DamageContext context) {
        return calculate(context, 0.0D);
    }

    /**
     * 一撃だけ命中補正を加えてダメージを計算します。補正は攻撃者の命中率へ加算し、
     * それ以外のダメージ計算には影響させません。
     *
     * @param context ダメージ計算入力
     * @param attackerAccuracyBonus この一撃だけ攻撃者の命中率へ加算する補正値（%ポイント）
     * @return 計算結果
     */
    public @NotNull DamageResult calculate(
            @NotNull DamageContext context,
            double attackerAccuracyBonus
    ) {
        HitCheck hitCheck = checkHit(context, attackerAccuracyBonus);
        if (!hitCheck.hit()) {
            return DamageResult.evaded(hitCheck.hitChance(), hitCheck.accuracy(), hitCheck.evasion());
        }

        List<DamageComponent> components = context.components().stream()
                .filter(component -> component.ratio() > 0.0D)
                .toList();
        double totalRatio = components.stream().mapToDouble(DamageComponent::ratio).sum();
        double resolvedAttackPower = Math.max(0.0D, resolveBaseDamage(context));
        DefenseCalculation defense = defenseCalculation(context.attacker(), context.victim(), context.attackType());
        if (totalRatio <= 0.0D) {
            return new DamageResult(
                    0.0D,
                    false,
                    hitCheck.hitChance(),
                    hitCheck.accuracy(),
                    hitCheck.evasion(),
                    new DamageBreakdown(
                            resolvedAttackPower,
                            defense.rawDefense(),
                            defense.effectiveDefense(),
                            List.of()
                    )
            );
        }

        double damage = resolvedAttackPower
                * totalRatio
                * sourceDamageMultiplier(context)
                * context.attackerDamageMultiplier();
        CriticalDamage criticalDamage = applyCriticalMultipliers(context, damage);
        damage = criticalDamage.damage();

        if (context.victim().isManaged() && damage > 0.0D) {
            damage *= defenseDamageMultiplier(resolvedAttackPower, defense.effectiveDefense());
        }

        double attributedDamage = 0.0D;
        Map<DamageElement, ElementCalculation> elementCalculations = new LinkedHashMap<>();
        for (DamageComponent component : components) {
            double share = damage * component.ratio() / totalRatio;
            ElementCalculation element = elementCalculations.computeIfAbsent(
                    component.element(),
                    damageElement -> elementCalculation(context, damageElement)
            );
            attributedDamage += share * element.multiplier();
        }

        return new DamageResult(
                Math.max(0.0D, attributedDamage),
                criticalDamage.critical(),
                criticalDamage.superStarCritical(),
                hitCheck.hitChance(),
                hitCheck.accuracy(),
                hitCheck.evasion(),
                new DamageBreakdown(
                        resolvedAttackPower,
                        defense.rawDefense(),
                        defense.effectiveDefense(),
                        elementCalculations.values().stream()
                                .map(ElementCalculation::resistance)
                                .filter(Objects::nonNull)
                                .toList()
                )
        );
    }

    private @NotNull HitCheck checkHit(
            @NotNull DamageContext context,
            double attackerAccuracyBonus
    ) {
        if (context.scaling() != DamageScaling.ATTACKER_STATUS
                || context.attacker() == null
                || !context.attacker().isManaged()) {
            return new HitCheck(true, 100.0D, 100.0D, 0.0D);
        }

        double normalizedAccuracyBonus = Double.isFinite(attackerAccuracyBonus)
                ? Math.max(0.0D, attackerAccuracyBonus)
                : 0.0D;
        double configuredAccuracy = context.attacker().statValue(StatusType.ACCURACY);
        double accuracy = context.attacker().isMob() && configuredAccuracy <= 0.0D
                ? DEFAULT_ACCURACY
                : Math.max(0.0D, configuredAccuracy);
        accuracy += normalizedAccuracyBonus;
        double evasion = context.victim().isManaged()
                ? Math.max(0.0D, context.victim().statValue(StatusType.EVASION))
                : 0.0D;
        double hitChance = calculateHitChance(accuracy, evasion);
        boolean hit = hitChance >= 100.0D || hitRollSupplier.getAsDouble() < hitChance;
        return new HitCheck(hit, hitChance, accuracy, evasion);
    }

    /**
     * 命中率と回避率から最終命中率を算出します。
     *
     * @param accuracy 攻撃者の命中率
     * @param evasion 被弾者の回避率
     * @return 1～100の最終命中率
     */
    public static double calculateHitChance(double accuracy, double evasion) {
        return Math.max(1.0D, Math.min(100.0D, accuracy - evasion));
    }

    /**
     * 通常会心と超星会心を独立して判定し、成立した倍率をダメージへ適用します。
     * 通常判定の超星会心は100%へ設定倍率を加算し、追尾弾の強制適用は設定倍率だけを
     * 乗算します。
     *
     * @param context ダメージ計算入力
     * @param damage 会心適用前ダメージ
     * @return 会心適用後ダメージと成立情報
     */
    private @NotNull CriticalDamage applyCriticalMultipliers(@NotNull DamageContext context, double damage) {
        if (context.attacker() == null || !context.attacker().isManaged() || damage <= 0.0D) {
            return new CriticalDamage(damage, false, false);
        }

        double criticalRate = Math.max(0.0D, context.attacker().statValue(StatusType.CRITICAL_RATE));
        boolean critical = criticalRate > 0.0D && criticalRollSupplier.getAsDouble() < criticalRate;
        if (critical) {
            double criticalDamage = context.attacker().statValue(StatusType.CRITICAL_DAMAGE);
            damage *= (criticalDamage <= 0.0D ? DEFAULT_CRITICAL_DAMAGE : criticalDamage) / 100.0D;
        }

        boolean superStarCritical = shouldApplySuperStarCritical(context);
        if (superStarCritical) {
            double configuredMultiplier = context.attacker().statValue(StatusType.SUPER_CRITICAL_DAMAGE);
            double multiplierPercent = configuredMultiplier <= 0.0D
                    ? DEFAULT_SUPER_STAR_CRITICAL_MULTIPLIER
                    : configuredMultiplier;
            double multiplier = multiplierPercent / 100.0D;
            if (context.superStarCriticalMode() == SuperStarCriticalMode.ROLL) {
                multiplier += 1.0D;
            }
            damage *= multiplier;
        }
        return new CriticalDamage(damage, critical, superStarCritical);
    }

    /**
     * コンテキストに従い超星会心倍率を適用するか判定します。
     *
     * @param context ダメージ計算入力
     * @return 倍率を適用する場合は {@code true}
     */
    private boolean shouldApplySuperStarCritical(@NotNull DamageContext context) {
        var attacker = context.attacker();
        if (attacker == null || !attacker.isPlayer() || !context.victim().isMob()
                || context.superStarCriticalMode() == SuperStarCriticalMode.DISABLED) {
            return false;
        }
        if (context.superStarCriticalMode() == SuperStarCriticalMode.FORCE) {
            return true;
        }
        double configuredRate = context.superStarCriticalRateOverride() == null
                ? attacker.statValue(StatusType.SUPER_CRITICAL_RATE)
                : context.superStarCriticalRateOverride();
        double rate = Math.max(0.0D, configuredRate);
        return rate > 0.0D && criticalRollSupplier.getAsDouble() < rate;
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
        return calculateAttackPower(context.attacker(), context.attackType());
    }

    /**
     * 攻撃種別に対応する基本能力値と種別攻撃力を反映した解決攻撃力を返します。
     *
     * @param attacker 攻撃者
     * @param attackType 攻撃種別
     * @return 解決攻撃力
     */
    public static double calculateAttackPower(
            @NotNull AstEntity attacker,
            @NotNull AttackType attackType
    ) {
        double attack = attacker.statValue(StatusType.ATTACK);
        double typedAttack = attacker.statValue(attackType.statusType());
        double primary = attacker.statValue(attackType.primaryStatusType());
        return (attack + typedAttack) * (1.0D + primary / 100.0D);
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
        return defenseCalculation(attacker, victim, attackType).effectiveDefense();
    }

    /**
     * 解決攻撃力と有効防御力の比率から、防御適用後に残るダメージ倍率を算出します。
     * 攻防が同値なら50%、防御が攻撃の2.5倍以上なら0%となります。
     *
     * @param resolvedAttackPower ratio・発生元倍率・会心を適用する前の解決攻撃力
     * @param effectiveDefense 貫通適用後の有効防御力
     * @return 0～1のダメージ倍率
     */
    static double defenseDamageMultiplier(double resolvedAttackPower, double effectiveDefense) {
        if (!Double.isFinite(resolvedAttackPower) || resolvedAttackPower <= 0.0D) {
            return 0.0D;
        }
        double defenseRatio = Math.max(0.0D, effectiveDefense) / resolvedAttackPower;
        if (defenseRatio >= FULL_BLOCK_DEFENSE_RATIO) {
            return 0.0D;
        }
        double remainingRatio = Math.max(0.0D, 1.0D - defenseRatio / FULL_BLOCK_DEFENSE_RATIO);
        return Math.pow(remainingRatio, DEFENSE_CURVE_EXPONENT);
    }

    private static @NotNull DefenseCalculation defenseCalculation(
            @Nullable io.github.maaasu.astralRecord.feature.combat.model.AstEntity attacker,
            @NotNull io.github.maaasu.astralRecord.feature.combat.model.AstEntity victim,
            @NotNull io.github.maaasu.astralRecord.feature.combat.model.AttackType attackType
    ) {
        double rawGeneralDefense = Math.max(0.0D, victim.statValue(StatusType.DEFENSE));
        double rawTypedDefense = Math.max(0.0D, victim.statValue(defenseStatusType(attackType)));
        double generalDefense = effectiveDefense(
                rawGeneralDefense,
                attacker == null ? 0.0D : attacker.statValue(StatusType.DEFENSE_PENETRATION_RATE)
        );
        double typedDefense = effectiveDefense(
                rawTypedDefense,
                attacker == null ? 0.0D : attacker.statValue(defensePenetrationStatusType(attackType))
        );
        return new DefenseCalculation(
                rawGeneralDefense + rawTypedDefense,
                generalDefense + typedDefense
        );
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

    private @NotNull ElementCalculation elementCalculation(
            @NotNull DamageContext context,
            @NotNull DamageElement element
    ) {
        if (element == DamageElement.NONE) {
            return new ElementCalculation(1.0D, null);
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
        return new ElementCalculation(
                increaseMultiplier * resistanceMultiplier,
                new DamageBreakdown.ElementResistance(element, resistance, effectiveResistance)
        );
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

    private record CriticalDamage(double damage, boolean critical, boolean superStarCritical) {}

    private record HitCheck(boolean hit, double hitChance, double accuracy, double evasion) {}

    private record DefenseCalculation(double rawDefense, double effectiveDefense) {}

    private record ElementCalculation(
            double multiplier,
            @Nullable DamageBreakdown.ElementResistance resistance
    ) {}
}
