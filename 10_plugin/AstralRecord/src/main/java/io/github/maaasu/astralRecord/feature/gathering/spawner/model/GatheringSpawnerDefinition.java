package io.github.maaasu.astralRecord.feature.gathering.spawner.model;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public record GatheringSpawnerDefinition(
        @NotNull String id,
        double radiusMeters,
        @NotNull List<GatheringSpawnerEntry> spawnGatherings,
        @NotNull List<GatheringSpawnerTimeWindow> timeWindows,
        @NotNull Material itemMaterial,
        long spawnIntervalTicks,
        int maxAlivePerSpawner,
        int maxNearbyGatherings,
        int spawnPerPlayer,
        @NotNull List<Material> requiredBaseBlocks
) {

    public GatheringSpawnerDefinition {
        radiusMeters = Math.max(1.0D, radiusMeters);
        spawnGatherings = spawnGatherings == null ? List.of() : List.copyOf(spawnGatherings);
        timeWindows = timeWindows == null || timeWindows.isEmpty()
                ? List.of(GatheringSpawnerTimeWindow.allDay())
                : List.copyOf(timeWindows);
        spawnIntervalTicks = Math.max(1L, spawnIntervalTicks);
        maxAlivePerSpawner = Math.max(1, maxAlivePerSpawner);
        maxNearbyGatherings = Math.max(1, maxNearbyGatherings);
        spawnPerPlayer = Math.max(1, spawnPerPlayer);
        requiredBaseBlocks = requiredBaseBlocks == null ? List.of() : List.copyOf(requiredBaseBlocks);
    }

    public boolean canSpawnAt(long worldTime) {
        return timeWindows.stream().anyMatch(window -> window.contains(worldTime));
    }

    public int desiredAliveCount(int nearbyPlayers) {
        return Math.min(maxAlivePerSpawner, Math.max(1, nearbyPlayers) * spawnPerPlayer);
    }
}
