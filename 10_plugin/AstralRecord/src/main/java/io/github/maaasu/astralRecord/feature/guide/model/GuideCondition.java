package io.github.maaasu.astralRecord.feature.guide.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * ガイド手順の達成条件です。
 *
 * @param type 条件種別
 * @param targetId 対象 ID。未指定時は同種イベントの全対象に一致
 * @param targetLevel 対象 Mob のレベル。未指定時は全レベルに一致
 */
public record GuideCondition(
    @NotNull GuideConditionType type,
    @Nullable String targetId,
    @Nullable Integer targetLevel
) {
    /** 既存の ID のみの条件を維持するコンストラクタです。 */
    public GuideCondition(@NotNull GuideConditionType type, @Nullable String targetId) {
        this(type, targetId, null);
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

    /** 発生したイベントが対象 ID とレベルの両方に一致するか判定します。 */
    public boolean matches(
        @NotNull GuideConditionType eventType,
        @Nullable String eventTargetId,
        @Nullable Integer eventTargetLevel
    ) {
        if (type != eventType) {
            return false;
        }
        if (targetId != null && !targetId.isBlank()
            && !targetId.equalsIgnoreCase(eventTargetId == null ? "" : eventTargetId)) {
            return false;
        }
        return targetLevel == null || targetLevel.equals(eventTargetLevel);
    }
}
