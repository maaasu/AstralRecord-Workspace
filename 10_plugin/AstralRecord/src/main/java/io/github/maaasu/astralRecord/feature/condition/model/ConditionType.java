package io.github.maaasu.astralRecord.feature.condition.model;

import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** 新仕様で扱う状態異常種別です。 */
public enum ConditionType {
    BURNING("燃焼", ConditionCategory.DOT, 100,
            effect(20, 0.0D, false, 0.20D, 1.0D, 1.0D, 1.0D, false, false, false, false, false, 0, 0, 0),
            StatusType.BURNING_APPLY_CHANCE, StatusType.BURNING_RESISTANCE,
            StatusType.BURNING_DAMAGE_INCREASE, StatusType.BURNING_DAMAGE_RESISTANCE,
            StatusType.BURNING_DAMAGE_PENETRATION),
    FROZEN("凍結", ConditionCategory.CONTROL, 40,
            effect(0, 0.0D, false, 0.0D, 1.0D, 1.0D, 1.0D, true, true, true, true, false, 0, 0, 0),
            StatusType.FROZEN_APPLY_CHANCE, StatusType.FROZEN_RESISTANCE, null, null, null),
    CHILLED("冷気", ConditionCategory.CONTROL, 100,
            effect(0, 0.0D, false, 0.0D, 0.5D, 2.0D, 1.0D, false, false, false, false, false, 0, 0, 0),
            StatusType.CHILLED_APPLY_CHANCE, StatusType.CHILLED_RESISTANCE, null, null, null),
    SHOCKED("感電", ConditionCategory.DOT, 100,
            effect(20, 0.0D, false, 0.10D, 1.0D, 1.0D, 1.0D, false, false, false, false, false, 16, 32, 6),
            StatusType.SHOCKED_APPLY_CHANCE, StatusType.SHOCKED_RESISTANCE,
            StatusType.SHOCKED_DAMAGE_INCREASE, StatusType.SHOCKED_DAMAGE_RESISTANCE,
            StatusType.SHOCKED_DAMAGE_PENETRATION),
    POISON("毒", ConditionCategory.DOT, 120,
            effect(20, 0.0D, false, 0.16D, 1.0D, 1.0D, 1.0D, false, false, false, false, false, 0, 0, 0),
            StatusType.POISONED_APPLY_CHANCE, StatusType.POISONED_RESISTANCE,
            StatusType.POISONED_DAMAGE_INCREASE, StatusType.POISONED_DAMAGE_RESISTANCE,
            StatusType.POISONED_DAMAGE_PENETRATION),
    BLINDNESS("盲目", ConditionCategory.CONTROL, 100,
            effect(0, 0.0D, false, 0.0D, 1.0D, 1.0D, 1.0D, false, false, false, false, false, 0, 0, 0),
            StatusType.BLINDNESS_APPLY_CHANCE, StatusType.BLINDNESS_RESISTANCE, null, null, null),
    WEAKNESS("衰弱", ConditionCategory.AMPLIFIER, 100,
            effect(0, 0.0D, false, 0.0D, 1.0D, 1.0D, 0.5D, false, false, false, false, false, 0, 0, 0),
            StatusType.WEAKNESS_APPLY_CHANCE, StatusType.WEAKNESS_RESISTANCE, null, null, null),
    HEALING_INHIBITION("回復阻害", ConditionCategory.AMPLIFIER, 100,
            effect(0, 0.0D, false, 0.0D, 1.0D, 1.0D, 1.0D, false, false, false, false, true, 0, 0, 0),
            StatusType.HEALING_INHIBITION_APPLY_CHANCE, StatusType.HEALING_INHIBITION_RESISTANCE,
            null, null, null);

    private final String displayName;
    private final ConditionCategory category;
    private final long defaultDurationTicks;
    private final ConditionEffect defaultEffect;
    private final StatusType applyChanceStatus;
    private final StatusType resistanceStatus;
    private final StatusType damageIncreaseStatus;
    private final StatusType damageResistanceStatus;
    private final StatusType damagePenetrationStatus;

    ConditionType(
            @NotNull String displayName,
            @NotNull ConditionCategory category,
            long defaultDurationTicks,
            @NotNull ConditionEffect defaultEffect,
            @NotNull StatusType applyChanceStatus,
            @NotNull StatusType resistanceStatus,
            @Nullable StatusType damageIncreaseStatus,
            @Nullable StatusType damageResistanceStatus,
            @Nullable StatusType damagePenetrationStatus
    ) {
        this.displayName = displayName;
        this.category = category;
        this.defaultDurationTicks = defaultDurationTicks;
        this.defaultEffect = defaultEffect;
        this.applyChanceStatus = applyChanceStatus;
        this.resistanceStatus = resistanceStatus;
        this.damageIncreaseStatus = damageIncreaseStatus;
        this.damageResistanceStatus = damageResistanceStatus;
        this.damagePenetrationStatus = damagePenetrationStatus;
    }

    public @NotNull String displayName() { return displayName; }
    public @NotNull ConditionCategory category() { return category; }
    public long defaultDurationTicks() { return defaultDurationTicks; }
    public @NotNull ConditionEffect defaultEffect() { return defaultEffect; }
    public @NotNull StatusType applyChanceStatus() { return applyChanceStatus; }
    public @NotNull StatusType resistanceStatus() { return resistanceStatus; }
    public @Nullable StatusType damageIncreaseStatus() { return damageIncreaseStatus; }
    public @Nullable StatusType damageResistanceStatus() { return damageResistanceStatus; }
    public @Nullable StatusType damagePenetrationStatus() { return damagePenetrationStatus; }

    /**
     * 文字列から状態異常種別を解決します。
     *
     * @param raw 入力値
     * @return 解決結果。不正値はnull
     */
    public static @Nullable ConditionType from(@Nullable Object raw) {
        if (raw == null) return null;
        try {
            return valueOf(raw.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static @NotNull ConditionEffect effect(
            int tickIntervalTicks,
            double healthRate,
            boolean currentHealthBased,
            double sourcePowerCoefficient,
            double movementMultiplier,
            double castTimeMultiplier,
            double damageDealtMultiplier,
            boolean movementBlocked,
            boolean attackBlocked,
            boolean skillBlocked,
            boolean aiBlocked,
            boolean healingBlocked,
            int controlIntervalMinTicks,
            int controlIntervalMaxTicks,
            int controlDurationTicks
    ) {
        return new ConditionEffect(
                tickIntervalTicks, healthRate, currentHealthBased, 0.0D, sourcePowerCoefficient,
                movementMultiplier, castTimeMultiplier, damageDealtMultiplier,
                movementBlocked, attackBlocked, skillBlocked, aiBlocked, healingBlocked,
                controlIntervalMinTicks, controlIntervalMaxTicks, controlDurationTicks
        );
    }
}
