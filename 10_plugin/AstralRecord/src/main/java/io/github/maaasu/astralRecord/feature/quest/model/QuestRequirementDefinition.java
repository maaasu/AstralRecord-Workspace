package io.github.maaasu.astralRecord.feature.quest.model;

import org.jetbrains.annotations.NotNull;

public record QuestRequirementDefinition(
    @NotNull QuestItemStackDefinition item,
    boolean consume
) {
}
