package io.github.maaasu.astralRecord.feature.waystone.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * ウェイストーンのマスタ定義です。
 *
 * @param id 内部管理用の一意ID
 * @param name 表示名
 * @param worldName 配置ワールド名
 * @param x X座標
 * @param y Y座標
 * @param z Z座標
 * @param yaw テレポート後の向き
 * @param pitch テレポート後の上下角
 * @param alwaysUnlocked 常時開放する場合はtrue
 * @param unlockGoldCost 初回開放に必要なゴールド
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
    boolean alwaysUnlocked,
    long unlockGoldCost
) {
    /**
     * BukkitのLocationへ変換します。
     *
     * @return ワールドがロード済みの場合はLocation、未ロードの場合はnull
     */
    public @Nullable Location toLocation() {
        var world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, x, y, z, yaw, pitch);
    }
}
