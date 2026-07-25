package io.github.maaasu.astralRecord.feature.condition.model;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 状態異常付与要求です。 */
public record ConditionApplyRequest(
        @NotNull AstEntity target,
        @Nullable AstEntity source,
        @NotNull ConditionType type,
        long durationTicks,
        double chance,
        double strength,
        @Nullable Double basePower,
        @Nullable Double powerCoefficient,
        @Nullable Double healthRate,
        @Nullable Integer tickIntervalTicks,
        @NotNull ConditionApplyReason reason
) {
    public ConditionApplyRequest {
        durationTicks = durationTicks <= 0L ? type.defaultDurationTicks() : durationTicks;
        chance = Math.max(0.0D, Math.min(100.0D, chance));
        strength = Math.max(0.0D, strength);
    }
}
