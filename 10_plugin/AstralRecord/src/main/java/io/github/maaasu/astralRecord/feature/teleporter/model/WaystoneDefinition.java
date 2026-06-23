package io.github.maaasu.astralRecord.feature.teleporter.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * Plugin data folder の waystones.yml に保存するウェイストーン定義です。
 */
public record WaystoneDefinition(
        @NotNull String id,
        @NotNull String name,
        @NotNull String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        boolean lockEnabled,
        long unlockGold,
        @NotNull Instant createdAt,
        @NotNull String createdBy
) {
    /**
     * Bukkit の Location へ変換します。
     *
     * @return ワールドがロードされている場合は Location、未ロードの場合は null
     */
    @Nullable
    public Location toLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, x, y, z, yaw, pitch);
    }

    /**
     * 表示および当たり判定の中心座標を返します。
     *
     * @return ワールドがロードされている場合は中心 Location、未ロードの場合は null
     */
    @Nullable
    public Location centerLocation() {
        Location location = toLocation();
        if (location == null) {
            return null;
        }
        return location.clone().add(0.5D, 0.9D, 0.5D);
    }
}
