package io.github.maaasu.astralRecord.feature.quest.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record QuestRewardDefinition(
    int exp,
    long gold,
    @NotNull List<QuestItemStackDefinition> items
) {
    public QuestRewardDefinition {
        exp = Math.max(0, exp);
        gold = Math.max(0L, gold);
        items = items == null ? List.of() : List.copyOf(items);
    }
}
