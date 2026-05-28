package io.github.maaasu.astralRecord.feature.world.model;

import org.jetbrains.annotations.NotNull;

/**
 * サーバー参加時の移動先設定です。
 *
 * @param world ワールド名
 * @param x X 座標
 * @param y Y 座標
 * @param z Z 座標
 * @param yaw yaw
 * @param pitch pitch
 */
public record JoinSpawnLocation(
        @NotNull String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
}
