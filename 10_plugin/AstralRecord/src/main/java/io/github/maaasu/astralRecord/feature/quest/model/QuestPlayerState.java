package io.github.maaasu.astralRecord.feature.quest.model;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class QuestPlayerState {
    private final UUID accountId;
    private final Map<String, QuestProgress> activeQuests;
    private final Map<String, Long> completedAt;
    private final Map<String, Long> cooldownUntil;

    public QuestPlayerState(
        @NotNull UUID accountId,
        @NotNull Map<String, QuestProgress> activeQuests,
        @NotNull Map<String, Long> completedAt,
        @NotNull Map<String, Long> cooldownUntil
    ) {
        this.accountId = accountId;
        this.activeQuests = new LinkedHashMap<>(activeQuests);
        this.completedAt = new LinkedHashMap<>(completedAt);
        this.cooldownUntil = new LinkedHashMap<>(cooldownUntil);
    }

    public @NotNull UUID accountId() {
        return accountId;
    }

    public @NotNull Map<String, QuestProgress> activeQuests() {
        return activeQuests;
    }

    public @NotNull Map<String, Long> completedAt() {
        return completedAt;
    }

    public @NotNull Map<String, Long> cooldownUntil() {
        return cooldownUntil;
    }

    public @NotNull QuestPlayerState snapshot() {
        Map<String, QuestProgress> activeSnapshot = new LinkedHashMap<>();
        for (Map.Entry<String, QuestProgress> entry : activeQuests.entrySet()) {
            QuestProgress progress = entry.getValue();
            activeSnapshot.put(entry.getKey(), new QuestProgress(
                progress.questId(),
                progress.acceptedAtEpochMillis(),
                progress.acceptedNpcId(),
                progress.objectiveProgress(),
                progress.readyToTurnIn()
            ));
        }
        return new QuestPlayerState(accountId, activeSnapshot, completedAt, cooldownUntil);
    }
}
