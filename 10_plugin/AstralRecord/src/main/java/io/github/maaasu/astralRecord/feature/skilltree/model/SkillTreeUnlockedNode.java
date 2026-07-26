package io.github.maaasu.astralRecord.feature.skilltree.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/** 解放済みノードと、その解放に使用した CP のクラスを表します。 */
public record SkillTreeUnlockedNode(
        @NotNull String nodeId,
        @Nullable String consumedClassId
) {
    public SkillTreeUnlockedNode {
        nodeId = Objects.requireNonNull(nodeId, "nodeId").trim();
        consumedClassId = consumedClassId == null || consumedClassId.isBlank()
                ? null
                : consumedClassId.trim().toLowerCase(Locale.ROOT);
    }
}
