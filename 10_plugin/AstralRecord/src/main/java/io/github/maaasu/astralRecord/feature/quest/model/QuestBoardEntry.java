package io.github.maaasu.astralRecord.feature.quest.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record QuestBoardEntry(
    @NotNull String questId,
    int page,
    @Nullable Integer slot,
    @Nullable Integer row,
    @Nullable Integer column
) {
    public QuestBoardEntry {
        questId = questId == null ? "" : questId.trim();
        page = Math.max(1, page);
    }
}
