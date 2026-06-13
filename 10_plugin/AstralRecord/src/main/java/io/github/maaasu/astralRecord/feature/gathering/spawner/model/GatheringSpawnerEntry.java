package io.github.maaasu.astralRecord.feature.gathering.spawner.model;

import org.jetbrains.annotations.NotNull;

public record GatheringSpawnerEntry(@NotNull String gatheringId, int weight) {
    public GatheringSpawnerEntry {
        weight = Math.max(1, weight);
    }
}
