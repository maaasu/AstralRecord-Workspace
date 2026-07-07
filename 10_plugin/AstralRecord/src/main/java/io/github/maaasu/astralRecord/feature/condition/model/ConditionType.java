package io.github.maaasu.astralRecord.feature.condition.model;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageType;
import org.jetbrains.annotations.NotNull;

/**
 * 初期実装で扱う状態異常種別を表します。
 */
public enum ConditionType {
    BURNING("燃焼", ConditionCategory.DOT, 100, 3, ConditionStackPolicy.STACK_POWER_REFRESH_DURATION,
            effect(20, 2.0D, 0.20D, 0.0D, 0.0D, 20.0D, 1.0D, 1.0D,
                    false, false, false, false, false, DamageType.MAGIC, DamageElement.FIRE)),
    POISON("毒", ConditionCategory.DOT, 120, 3, ConditionStackPolicy.STACK_POWER_REFRESH_DURATION,
            effect(20, 1.5D, 0.12D, 0.0D, 0.0D, 18.0D, 0.85D, 1.0D,
                    false, false, false, false, false, DamageType.MAGIC, DamageElement.POISON)),
    BLEEDING("出血", ConditionCategory.DOT, 100, 3, ConditionStackPolicy.STACK_POWER_REFRESH_DURATION,
            effect(20, 1.2D, 0.15D, 0.0D, 0.0D, 16.0D, 1.0D, 1.0D,
                    false, false, false, false, false, DamageType.PHYSICAL, DamageElement.NEUTRAL)),
    CHILLED("冷気", ConditionCategory.CONTROL, 120, 5, ConditionStackPolicy.STACK_POWER_REFRESH_DURATION,
            effect(0, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 1.0D, 1.0D,
                    false, false, false, false, false, DamageType.MAGIC, DamageElement.ICE)),
    FROZEN("凍結", ConditionCategory.CONTROL, 60, 1, ConditionStackPolicy.REFRESH_DURATION,
            effect(0, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 1.0D, 1.0D,
                    true, true, true, true, false, DamageType.MAGIC, DamageElement.ICE)),
    STUNNED("スタン", ConditionCategory.CONTROL, 40, 1, ConditionStackPolicy.REFRESH_DURATION,
            effect(0, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 1.0D, 1.0D,
                    true, true, true, true, false, DamageType.MAGIC, DamageElement.LIGHTNING)),
    SILENCED("沈黙", ConditionCategory.CONTROL, 100, 1, ConditionStackPolicy.REFRESH_DURATION,
            effect(0, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 1.0D, 1.0D,
                    false, false, true, true, false, DamageType.MAGIC, DamageElement.DARK)),
    ATTACK_DISABLED("攻撃不可", ConditionCategory.CONTROL, 80, 1, ConditionStackPolicy.REFRESH_DURATION,
            effect(0, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 1.0D, 1.0D,
                    false, true, false, false, false, DamageType.PHYSICAL, DamageElement.NEUTRAL)),
    INVULNERABLE("無敵", ConditionCategory.PROTECTION, 60, 1, ConditionStackPolicy.REFRESH_DURATION,
            effect(0, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 1.0D, 1.0D,
                    false, false, false, false, true, DamageType.TRUE, DamageElement.NEUTRAL)),
    VULNERABLE("脆弱", ConditionCategory.AMPLIFIER, 120, 3, ConditionStackPolicy.STACK_POWER_REFRESH_DURATION,
            effect(0, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 1.0D, 1.10D,
                    false, false, false, false, false, DamageType.TRUE, DamageElement.NEUTRAL));

    private final String displayName;
    private final ConditionCategory category;
    private final long defaultDurationTicks;
    private final int maxStack;
    private final ConditionStackPolicy stackPolicy;
    private final ConditionEffect defaultEffect;

    ConditionType(
            @NotNull String displayName,
            @NotNull ConditionCategory category,
            long defaultDurationTicks,
            int maxStack,
            @NotNull ConditionStackPolicy stackPolicy,
            @NotNull ConditionEffect defaultEffect
    ) {
        this.displayName = displayName;
        this.category = category;
        this.defaultDurationTicks = defaultDurationTicks;
        this.maxStack = maxStack;
        this.stackPolicy = stackPolicy;
        this.defaultEffect = defaultEffect;
    }

    /**
     * 表示名を返します。
     *
     * @return 表示名
     */
    public @NotNull String displayName() {
        return displayName;
    }

    /**
     * 分類を返します。
     *
     * @return 状態異常分類
     */
    public @NotNull ConditionCategory category() {
        return category;
    }

    /**
     * 既定 duration を tick 単位で返します。
     *
     * @return 既定 duration tick
     */
    public long defaultDurationTicks() {
        return defaultDurationTicks;
    }

    /**
     * 最大 stack 数を返します。
     *
     * @return 最大 stack
     */
    public int maxStack() {
        return maxStack;
    }

    /**
     * 重複付与ポリシーを返します。
     *
     * @return stack policy
     */
    public @NotNull ConditionStackPolicy stackPolicy() {
        return stackPolicy;
    }

    /**
     * 既定効果を返します。
     *
     * @return 既定効果
     */
    public @NotNull ConditionEffect defaultEffect() {
        return defaultEffect;
    }

    /**
     * 文字列から状態異常種別を解決します。
     *
     * @param raw 入力文字列
     * @return 解決した種別。不正値は null
     */
    public static ConditionType from(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return ConditionType.valueOf(raw.toString().trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static @NotNull ConditionEffect effect(
            int tickIntervalTicks,
            double basePower,
            double sourceAttackCoefficient,
            double sourceTypedAttackCoefficient,
            double targetMaxHealthCoefficient,
            double maxTickDamage,
            double healingReceivedMultiplier,
            double damageTakenMultiplier,
            boolean movementBlocked,
            boolean attackBlocked,
            boolean skillBlocked,
            boolean aiBlocked,
            boolean damageImmune,
            @NotNull DamageType damageType,
            @NotNull DamageElement damageElement
    ) {
        return new ConditionEffect(
                tickIntervalTicks,
                basePower,
                sourceAttackCoefficient,
                sourceTypedAttackCoefficient,
                targetMaxHealthCoefficient,
                maxTickDamage,
                healingReceivedMultiplier,
                damageTakenMultiplier,
                movementBlocked,
                attackBlocked,
                skillBlocked,
                aiBlocked,
                damageImmune,
                damageType,
                damageElement
        );
    }
}
