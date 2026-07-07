package io.github.maaasu.astralRecord.feature.condition.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 状態異常付与の結果を表します。
 */
public record ConditionApplyResult(
        boolean success,
        boolean updated,
        @Nullable ActiveCondition condition,
        @NotNull ConditionRejectReason rejectReason
) {
    public static @NotNull ConditionApplyResult applied(@NotNull ActiveCondition condition) {
        return new ConditionApplyResult(true, false, condition, ConditionRejectReason.NONE);
    }

    public static @NotNull ConditionApplyResult updated(@NotNull ActiveCondition condition) {
        return new ConditionApplyResult(true, true, condition, ConditionRejectReason.NONE);
    }

    public static @NotNull ConditionApplyResult rejected(@NotNull ConditionRejectReason reason) {
        return new ConditionApplyResult(false, false, null, reason);
    }
}
