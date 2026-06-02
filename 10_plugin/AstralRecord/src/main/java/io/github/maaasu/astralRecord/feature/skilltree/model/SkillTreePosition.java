package io.github.maaasu.astralRecord.feature.skilltree.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * スキルツリー構造内のポジション定義です。
 */
public record SkillTreePosition(
        @NotNull String positionId,
        @NotNull String worldName,
        int x,
        int y,
        int z
) {
    @NotNull
    public static SkillTreePosition from(@NotNull String positionId, @NotNull Location location) {
        String worldName = location.getWorld() == null ? "" : location.getWorld().getName();
        return new SkillTreePosition(
                positionId,
                worldName,
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
    }

    @NotNull
    public String locationKey() {
        return worldName + ":" + x + ":" + y + ":" + z;
    }

    @Nullable
    public Location toLocation() {
        var world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, x + 0.5D, y, z + 0.5D);
    }
}
