package io.github.maaasu.astralRecord.feature.textdisplay.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * プラグイン管理 YAML に保存する固定 TextDisplay 配置情報です。
 *
 * @param id        表示 ID
 * @param text      表示テキスト
 * @param worldName Bukkit ワールド名
 * @param x         X 座標
 * @param y         Y 座標
 * @param z         Z 座標
 * @param yaw       水平方向
 * @param pitch     垂直方向
 */
public record TextDisplayPlacement(
        @NotNull String id,
        @NotNull String text,
        @NotNull String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {

    /**
     * Bukkit Location に変換します。
     *
     * @return ワールドがロード済みの場合は Location、未ロードの場合は null
     */
    @Nullable
    public Location toLocation() {
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, x, y, z, yaw, pitch);
    }

    /**
     * Bukkit Location から固定 TextDisplay 配置情報を作成します。
     *
     * @param id       表示 ID
     * @param text     表示テキスト
     * @param location 配置座標
     * @return 固定 TextDisplay 配置情報
     */
    @NotNull
    public static TextDisplayPlacement from(@NotNull String id, @NotNull String text, @NotNull Location location) {
        World world = location.getWorld();
        return new TextDisplayPlacement(
                id,
                text,
                world == null ? "world" : world.getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch()
        );
    }
}
