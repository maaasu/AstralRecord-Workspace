package io.github.maaasu.astralRecord.feature.boss.model;

import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Defines a boss feature location in a master-data friendly form.
 */
public record BossLocation(
        @Nullable String worldId,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
    /**
     * Converts this definition into a Bukkit location in the supplied world.
     *
     * @param world resolved Bukkit world
     * @return Bukkit location
     */
    public @NotNull Location toLocation(@NotNull World world) {
        return new Location(world, x, y, z, yaw, pitch);
    }
}
