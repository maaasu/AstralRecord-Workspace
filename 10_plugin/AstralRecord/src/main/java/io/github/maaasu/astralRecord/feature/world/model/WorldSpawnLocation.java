package io.github.maaasu.astralRecord.feature.world.model;

import org.jetbrains.annotations.NotNull;

/**
 * WorldMasterData に紐づくスポーン地点座標です。
 *
 * @param x X 座標
 * @param y Y 座標
 * @param z Z 座標
 * @param yaw yaw
 * @param pitch pitch
 */
public record WorldSpawnLocation(
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {

    /**
     * 既定のスポーン地点を返します。
     *
     * @return 既定のスポーン地点
     */
    @NotNull
    public static WorldSpawnLocation defaultLocation() {
        return new WorldSpawnLocation(0.5D, 64.0D, 0.5D, 0.0F, 0.0F);
    }
}
