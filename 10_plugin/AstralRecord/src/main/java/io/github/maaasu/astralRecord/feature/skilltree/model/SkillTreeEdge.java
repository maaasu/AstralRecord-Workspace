package io.github.maaasu.astralRecord.feature.skilltree.model;

import org.jetbrains.annotations.NotNull;

/**
 * スキルツリーのポジション同士の接続定義です。
 */
public record SkillTreeEdge(@NotNull String leftPositionId, @NotNull String rightPositionId) {
    public SkillTreeEdge(@NotNull String leftPositionId, @NotNull String rightPositionId) {
        if (leftPositionId.compareTo(rightPositionId) <= 0) {
            this.leftPositionId = leftPositionId;
            this.rightPositionId = rightPositionId;
        } else {
            this.leftPositionId = rightPositionId;
            this.rightPositionId = leftPositionId;
        }
    }

    @NotNull
    public String key() {
        return leftPositionId + "->" + rightPositionId;
    }

    public boolean contains(@NotNull String positionId) {
        return leftPositionId.equals(positionId) || rightPositionId.equals(positionId);
    }
}
