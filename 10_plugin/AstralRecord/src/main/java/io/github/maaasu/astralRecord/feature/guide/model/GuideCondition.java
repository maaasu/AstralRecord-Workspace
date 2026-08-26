package io.github.maaasu.astralRecord.feature.guide.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * ガイド手順の達成条件です。
 *
 * @param type 条件種別
 * @param targetId 対象 ID。未指定時は同種イベントの全対象に一致
 * @param targetIds 対象 ID の候補一覧。指定時はいずれかに一致
 * @param targetLevel 対象 Mob のレベル。未指定時は全レベルに一致
 */
public record GuideCondition(
    @NotNull GuideConditionType type,
    @Nullable String targetId,
    @NotNull List<String> targetIds,
    @Nullable Integer targetLevel
) {
    public GuideCondition {
        targetIds = targetIds == null
            ? List.of()
            : targetIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }

    /** 既存の ID のみの条件を維持するコンストラクタです。 */
    public GuideCondition(@NotNull GuideConditionType type, @Nullable String targetId) {
        this(type, targetId, List.of(), null);
    }

    /** 既存の ID とレベルを持つ条件を維持するコンストラクタです。 */
    public GuideCondition(
        @NotNull GuideConditionType type,
        @Nullable String targetId,
        @Nullable Integer targetLevel
    ) {
        this(type, targetId, List.of(), targetLevel);
    }

    /**
     * 発生したゲームイベントが条件に一致するか判定します。
     *
     * @param eventType 発生した条件種別
     * @param eventTargetId 発生イベントの対象 ID
     * @return 一致する場合は true
     */
    public boolean matches(@NotNull GuideConditionType eventType, @Nullable String eventTargetId) {
        return matches(eventType, eventTargetId, null);
    }

    /** 発生したイベントが対象 ID（単一または候補一覧）とレベルの両方に一致するか判定します。 */
    public boolean matches(
        @NotNull GuideConditionType eventType,
        @Nullable String eventTargetId,
        @Nullable Integer eventTargetLevel
    ) {
        if (type != eventType) {
            return false;
        }
        boolean hasTargetId = targetId != null && !targetId.isBlank();
        boolean hasTargetIds = !targetIds.isEmpty();
        if ((hasTargetId || hasTargetIds) && !matchesTarget(eventTargetId)) {
            return false;
        }
        return targetLevel == null || targetLevel.equals(eventTargetLevel);
    }

    private boolean matchesTarget(@Nullable String eventTargetId) {
        String actual = eventTargetId == null ? "" : eventTargetId;
        if (targetId != null && !targetId.isBlank() && targetId.equalsIgnoreCase(actual)) {
            return true;
        }
        return targetIds.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(actual));
    }
}
