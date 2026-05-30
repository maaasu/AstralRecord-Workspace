package io.github.maaasu.astralRecord.feature.mob.spawner.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * プラグインデータフォルダ配下に保存するスポナー座標です。
 *
 * @param spawnerId スポナー定義 ID
 * @param worldName Bukkit ワールド名
 * @param x         ブロック X
 * @param y         ブロック Y
 * @param z         ブロック Z
 */
public record MobSpawnerLocation(
        @NotNull String spawnerId,
        @NotNull String worldName,
        int x,
        int y,
        int z
) {

    /**
     * 座標の一意キーを返します。スポナー ID は含めず、同一座標重複を抑止します。
     *
     * @return 座標キー
     */
    @NotNull
    public String locationKey() {
        return worldName + ":" + x + ":" + y + ":" + z;
    }

    /**
     * Bukkit Location に変換します。
     *
     * @return ワールドが存在する場合は Location、なければ null
     */
    @Nullable
    public Location toLocation() {
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, x + 0.5D, y, z + 0.5D);
    }

    /**
     * Bukkit Location からブロック座標を作成します。
     *
     * @param spawnerId スポナー ID
     * @param location  Bukkit Location
     * @return スポナー座標
     */
    @NotNull
    public static MobSpawnerLocation from(@NotNull String spawnerId, @NotNull Location location) {
        World world = location.getWorld();
        return new MobSpawnerLocation(
                spawnerId,
                world == null ? "world" : world.getName(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
    }
}
