package io.github.maaasu.astralRecord.feature.skilltree.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * スキルツリー構造内のポジション定義です。
 */
public record SkillTreePosition(
        @NotNull String nodeId,
        @NotNull String worldName,
        int x,
        int y,
        int z
) {
    @NotNull
    public String locationKey() {
        return worldName + ":" + x + ":" + y + ":" + z;
    }

    @Nullable
    public Location toLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            world = Bukkit.getWorld(new File(worldName).getName());
        }
        if (world == null) {
            String normalized = worldName.replace('\\', '/');
            for (World candidate : Bukkit.getWorlds()) {
                String folderPath = candidate.getWorldFolder().getPath().replace('\\', '/');
                if (folderPath.endsWith(normalized)) {
                    world = candidate;
                    break;
                }
            }
        }
        if (world == null) {
            return null;
        }
        return new Location(world, x + 0.5D, y, z + 0.5D);
    }
}
