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
    private int persistedVersion;

    public QuestPlayerState(
        @NotNull UUID accountId,
        @NotNull Map<String, QuestProgress> activeQuests,
        @NotNull Map<String, Long> completedAt,
        @NotNull Map<String, Long> cooldownUntil
    ) {
        this(accountId, activeQuests, completedAt, cooldownUntil, 0);
    }

    public QuestPlayerState(
        @NotNull UUID accountId,
        @NotNull Map<String, QuestProgress> activeQuests,
        @NotNull Map<String, Long> completedAt,
        @NotNull Map<String, Long> cooldownUntil,
        int persistedVersion
    ) {
        this.accountId = accountId;
        this.activeQuests = new LinkedHashMap<>(activeQuests);
        this.completedAt = new LinkedHashMap<>(completedAt);
        this.cooldownUntil = new LinkedHashMap<>(cooldownUntil);
        this.persistedVersion = Math.max(0, persistedVersion);
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

    public int persistedVersion() {
        return persistedVersion;
    }

    public void acknowledgePersistedVersion(int version) {
        persistedVersion = Math.max(0, version);
    }

    public void restore(@NotNull QuestPlayerState snapshot) {
        activeQuests.clear();
        snapshot.activeQuests.forEach((id, progress) -> activeQuests.put(id, copyProgress(progress)));
        completedAt.clear();
        completedAt.putAll(snapshot.completedAt);
        cooldownUntil.clear();
        cooldownUntil.putAll(snapshot.cooldownUntil);
        persistedVersion = snapshot.persistedVersion;
    }

    public @NotNull QuestPlayerState snapshot() {
        Map<String, QuestProgress> activeSnapshot = new LinkedHashMap<>();
        for (Map.Entry<String, QuestProgress> entry : activeQuests.entrySet()) {
            activeSnapshot.put(entry.getKey(), copyProgress(entry.getValue()));
        }
        return new QuestPlayerState(accountId, activeSnapshot, completedAt, cooldownUntil, persistedVersion);
    }

    private static @NotNull QuestProgress copyProgress(@NotNull QuestProgress progress) {
        return new QuestProgress(
            progress.questId(),
            progress.acceptedAtEpochMillis(),
            progress.acceptedNpcId(),
            progress.objectiveProgress(),
            progress.readyToTurnIn()
        );
    }
}
