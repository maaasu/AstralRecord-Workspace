package io.github.maaasu.astralRecord.feature.skilltree.model;

import org.jetbrains.annotations.NotNull;

/**
 * スキルツリーノード同士の無向接続定義です。
 */
public record SkillTreeEdge(@NotNull String sourceNodeId, @NotNull String targetNodeId) {
    public SkillTreeEdge(@NotNull String sourceNodeId, @NotNull String targetNodeId) {
        if (sourceNodeId.compareTo(targetNodeId) <= 0) {
            this.sourceNodeId = sourceNodeId;
            this.targetNodeId = targetNodeId;
        } else {
            this.sourceNodeId = targetNodeId;
            this.targetNodeId = sourceNodeId;
        }
    }

    @NotNull
    public String key() {
        return sourceNodeId + "->" + targetNodeId;
    }

    public boolean contains(@NotNull String nodeId) {
        return sourceNodeId.equals(nodeId) || targetNodeId.equals(nodeId);
    }
}
