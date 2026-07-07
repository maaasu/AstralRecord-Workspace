package io.github.maaasu.astralRecord.feature.quest.model;

import org.jetbrains.annotations.NotNull;

public record QuestObjectiveDefinition(
    @NotNull String id,
    @NotNull QuestObjectiveType type,
    @NotNull String targetId,
    @NotNull String label,
    int amount
) {
    public QuestObjectiveDefinition {
        id = id == null || id.isBlank() ? type.name().toLowerCase() + ":" + targetId : id.trim();
        targetId = targetId == null ? "" : targetId.trim();
        label = label == null || label.isBlank() ? targetId : label.trim();
        amount = Math.max(1, amount);
    }
}
