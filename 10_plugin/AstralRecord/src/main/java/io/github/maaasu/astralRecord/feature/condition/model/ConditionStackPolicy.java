package io.github.maaasu.astralRecord.feature.condition.model;

/**
 * 同種状態異常が重複した場合の扱いを表します。
 */
public enum ConditionStackPolicy {
    REFRESH_DURATION,
    STACK_POWER_REFRESH_DURATION,
    REPLACE_IF_STRONGER,
    IGNORE_IF_ACTIVE
}
