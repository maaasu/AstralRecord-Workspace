package io.github.maaasu.astralRecord.feature.gathering.spawner.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record GatheringSpawnerLocation(@NotNull String spawnerId, @NotNull String worldName, int x, int y, int z) {

    public @NotNull String locationKey() {
        return worldName + ":" + x + ":" + y + ":" + z;
    }

    public @Nullable Location toLocation() {
        var world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, x, y, z);
    }

    public static @NotNull GatheringSpawnerLocation from(@NotNull String spawnerId, @NotNull Location location) {
        return new GatheringSpawnerLocation(
                spawnerId,
                location.getWorld() == null ? "world" : location.getWorld().getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
    }
}
