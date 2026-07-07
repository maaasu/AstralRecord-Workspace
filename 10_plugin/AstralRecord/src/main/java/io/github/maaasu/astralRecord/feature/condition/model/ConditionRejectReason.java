package io.github.maaasu.astralRecord.feature.condition.model;

/**
 * 状態異常付与が拒否された理由を表します。
 */
public enum ConditionRejectReason {
    NONE,
    UNMANAGED_TARGET,
    DEAD_TARGET,
    NPC_TARGET,
    RESISTED,
    CHANCE_FAILED,
    INVALID_DURATION
}
