package io.github.maaasu.astralRecord.feature.player.service;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;

/** 移動アクションで共有する、足元の衝突形状による接地判定です。 */
public final class PlayerGroundSupport {
    private static final double CONTACT_DISTANCE = 0.05D;
    private static final double SURFACE_EPSILON = 1.0E-6D;

    private PlayerGroundSupport() {
    }

    /**
     * 現在位置で足の範囲に接するブロックの上面があるか確認します。
     *
     * @param player 対象プレイヤー
     * @return 足元に衝突面があれば {@code true}
     * @implNote Bukkit メインスレッドで呼び出してください。チャンクを読み込みません。
     */
    public static boolean isGrounded(@NotNull Player player) {
        return isGrounded(player, player.getLocation());
    }

    /**
     * プレイヤーの当たり判定を指定位置へ平行移動し、足元の接地を確認します。
     * 移動イベントでは、更新前の現在位置ではなく移動先を判定できます。
     *
     * @param player 当たり判定を取得するプレイヤー
     * @param location 判定する足元の位置
     * @return 足元から下方 0.05 ブロック以内に上面があれば {@code true}
     * @implNote Bukkit メインスレッドで呼び出してください。入力と衝突形状を変更しません。
     */
    public static boolean isGrounded(@NotNull Player player, @NotNull Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }
        Location current = player.getLocation();
        BoundingBox body = player.getBoundingBox().clone().shift(
                location.getX() - current.getX(),
                location.getY() - current.getY(),
                location.getZ() - current.getZ()
        );
        int minX = (int) Math.floor(body.getMinX());
        int maxX = (int) Math.floor(body.getMaxX());
        int minZ = (int) Math.floor(body.getMinZ());
        int maxZ = (int) Math.floor(body.getMaxZ());
        // フェンス等は所属ブロックより上へ衝突形状が伸びるため、一段下も調べる。
        int minY = Math.max(world.getMinHeight(), (int) Math.floor(body.getMinY() - CONTACT_DISTANCE) - 1);
        int maxY = Math.min(world.getMaxHeight() - 1, (int) Math.floor(body.getMinY() + SURFACE_EPSILON));
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    for (BoundingBox local : world.getBlockAt(x, y, z).getCollisionShape().getBoundingBoxes()) {
                        if (hasGroundContact(body, local.clone().shift(x, y, z))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * 足の水平範囲と衝突面の上端から接地を判定します。側面への接触は接地にしません。
     *
     * @param body プレイヤーのワールド座標の当たり判定
     * @param support ブロックのワールド座標の衝突形状の一部
     * @return 水平範囲が重なり、上面が足元の直下にあれば {@code true}
     */
    static boolean hasGroundContact(@NotNull BoundingBox body, @NotNull BoundingBox support) {
        double gap = body.getMinY() - support.getMaxY();
        return support.getVolume() > 0.0D
                && gap >= -SURFACE_EPSILON
                && gap <= CONTACT_DISTANCE + SURFACE_EPSILON
                && body.getMinX() < support.getMaxX()
                && body.getMaxX() > support.getMinX()
                && body.getMinZ() < support.getMaxZ()
                && body.getMaxZ() > support.getMinZ();
    }
}
