package io.github.maaasu.astralRecord.feature.quest.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

public final class QuestProgress {
    private final String questId;
    private final long acceptedAtEpochMillis;
    private final String acceptedNpcId;
    private final Map<String, Integer> objectiveProgress;
    private boolean readyToTurnIn;

    public QuestProgress(
        @NotNull String questId,
        long acceptedAtEpochMillis,
        @Nullable String acceptedNpcId,
        @NotNull Map<String, Integer> objectiveProgress,
        boolean readyToTurnIn
    ) {
        this.questId = questId;
        this.acceptedAtEpochMillis = acceptedAtEpochMillis;
        this.acceptedNpcId = acceptedNpcId;
        this.objectiveProgress = new LinkedHashMap<>(objectiveProgress);
        this.readyToTurnIn = readyToTurnIn;
    }

    public static @NotNull QuestProgress start(@NotNull QuestDefinition quest, @Nullable String acceptedNpcId) {
        Map<String, Integer> progress = new LinkedHashMap<>();
        for (QuestObjectiveDefinition objective : quest.objectives()) {
            progress.put(objective.id(), 0);
        }
        return new QuestProgress(quest.id(), System.currentTimeMillis(), acceptedNpcId, progress, quest.objectives().isEmpty());
    }

    public @NotNull String questId() {
        return questId;
    }

    public long acceptedAtEpochMillis() {
        return acceptedAtEpochMillis;
    }

    public @Nullable String acceptedNpcId() {
        return acceptedNpcId;
    }

    public @NotNull Map<String, Integer> objectiveProgress() {
        return Map.copyOf(objectiveProgress);
    }

    public int progress(@NotNull String objectiveId) {
        return Math.max(0, objectiveProgress.getOrDefault(objectiveId, 0));
    }

    public void setProgress(@NotNull String objectiveId, int value) {
        objectiveProgress.put(objectiveId, Math.max(0, value));
    }

    public boolean readyToTurnIn() {
        return readyToTurnIn;
    }

    public void readyToTurnIn(boolean readyToTurnIn) {
        this.readyToTurnIn = readyToTurnIn;
    }
}
