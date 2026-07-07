package io.github.maaasu.astralRecord.feature.quest.model;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record QuestDefinition(
    @NotNull String id,
    @NotNull String name,
    @NotNull List<String> description,
    @NotNull Material icon,
    @NotNull QuestRepeatMode repeatMode,
    long cooldownSeconds,
    @NotNull QuestCompletionMode completionMode,
    @Nullable String turnInNpcId,
    @NotNull List<QuestObjectiveDefinition> objectives,
    @NotNull List<QuestRequirementDefinition> requirements,
    @NotNull QuestRewardDefinition rewards
) {
    public QuestDefinition {
        id = id == null ? "" : id.trim();
        name = name == null || name.isBlank() ? id : name.trim();
        description = description == null ? List.of() : List.copyOf(description);
        icon = icon == null ? Material.PAPER : icon;
        repeatMode = repeatMode == null ? QuestRepeatMode.ONCE : repeatMode;
        cooldownSeconds = Math.max(0L, cooldownSeconds);
        completionMode = completionMode == null ? QuestCompletionMode.NPC : completionMode;
        turnInNpcId = turnInNpcId == null || turnInNpcId.isBlank() ? null : turnInNpcId.trim();
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        rewards = rewards == null ? new QuestRewardDefinition(0, 0L, List.of()) : rewards;
    }

    public boolean isAutoReward() {
        return completionMode == QuestCompletionMode.AUTO;
    }
}
