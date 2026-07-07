package io.github.maaasu.astralRecord.feature.condition.model;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 状態異常付与の要求値を表します。
 */
public record ConditionApplyRequest(
        @NotNull AstEntity target,
        @Nullable AstEntity source,
        @NotNull ConditionType type,
        long durationTicks,
        double chance,
        int stack,
        @Nullable Double basePower,
        @Nullable Double powerCoefficient,
        @Nullable Integer tickIntervalTicks,
        @Nullable DamageType damageType,
        @Nullable DamageElement damageElement,
        @NotNull ConditionApplyReason reason
) {
    public ConditionApplyRequest {
        durationTicks = durationTicks <= 0L ? type.defaultDurationTicks() : durationTicks;
        chance = Math.max(0.0D, Math.min(100.0D, chance));
        stack = Math.max(1, stack);
    }
}
