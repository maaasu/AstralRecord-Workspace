package io.github.maaasu.astralRecord.feature.mob.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * プラグイン管理 YAML に保存する NPC 配置情報です。
 *
 * @param npcId     NPC マスタ ID
 * @param worldName Bukkit ワールド名
 * @param x         X 座標
 * @param y         Y 座標
 * @param z         Z 座標
 * @param yaw       水平方向
 * @param pitch     垂直方向
 */
public record NpcPlacement(
        @NotNull String npcId,
        @NotNull String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {

    /**
     * 同一配置を識別するキーを返します。
     *
     * @return ワールドと座標を含む配置キー
     */
    @NotNull
    public String locationKey() {
        return worldName + ":" + x + ":" + y + ":" + z;
    }

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
     * Bukkit Location から NPC 配置情報を作成します。
     *
     * @param npcId    NPC マスタ ID
     * @param location 配置座標
     * @return NPC 配置情報
     */
    @NotNull
    public static NpcPlacement from(@NotNull String npcId, @NotNull Location location) {
        World world = location.getWorld();
        return new NpcPlacement(
                npcId,
                world == null ? "world" : world.getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch()
        );
    }
}
