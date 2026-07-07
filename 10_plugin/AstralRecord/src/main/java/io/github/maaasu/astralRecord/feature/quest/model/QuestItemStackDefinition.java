package io.github.maaasu.astralRecord.feature.quest.model;

import org.jetbrains.annotations.NotNull;

public record QuestItemStackDefinition(
    @NotNull String itemId,
    @NotNull String category,
    int amount
) {
    public QuestItemStackDefinition {
        itemId = itemId == null ? "" : itemId.trim();
        category = category == null || category.isBlank() ? "material" : category.trim();
        amount = Math.max(1, amount);
    }
}
