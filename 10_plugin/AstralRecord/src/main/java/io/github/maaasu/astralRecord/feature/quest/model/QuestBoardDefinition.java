package io.github.maaasu.astralRecord.feature.quest.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record QuestBoardDefinition(
    @NotNull String id,
    @NotNull String name,
    @NotNull List<QuestBoardEntry> entries
) {
    public QuestBoardDefinition {
        id = id == null ? "" : id.trim();
        name = name == null || name.isBlank() ? id : name.trim();
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
