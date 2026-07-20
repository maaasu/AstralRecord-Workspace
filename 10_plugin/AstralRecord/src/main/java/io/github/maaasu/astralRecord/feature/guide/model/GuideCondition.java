package io.github.maaasu.astralRecord.feature.guide.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * ガイド手順の達成条件です。
 *
 * @param type 条件種別
 * @param targetId 対象 ID。未指定時は同種イベントの全対象に一致
 */
public record GuideCondition(
    @NotNull GuideConditionType type,
    @Nullable String targetId
) {
    /**
     * 発生したゲームイベントが条件に一致するか判定します。
     *
     * @param eventType 発生した条件種別
     * @param eventTargetId 発生イベントの対象 ID
     * @return 一致する場合は true
     */
    public boolean matches(@NotNull GuideConditionType eventType, @Nullable String eventTargetId) {
        if (type != eventType) {
            return false;
        }
        return targetId == null || targetId.isBlank() || targetId.equalsIgnoreCase(eventTargetId == null ? "" : eventTargetId);
    }
}
