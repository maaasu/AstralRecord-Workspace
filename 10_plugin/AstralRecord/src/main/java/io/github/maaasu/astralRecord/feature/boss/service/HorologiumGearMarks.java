package io.github.maaasu.astralRecord.feature.boss.service;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

/** 時計盤に固定された12個の歯車印の座標を、床と戦闘で共有します。 */
final class HorologiumGearMarks {
    static final double RADIUS = 1.5D;
    private static final double DIAGONAL = 16D / Math.sqrt(2D);
    static final List<Mark> ALL = List.of(
        new Mark(0D, -8D, 0, true),
        new Mark(8D, 0D, 90, true),
        new Mark(0D, 8D, 180, true),
        new Mark(-8D, 0D, 270, true),
        new Mark(0D, -16D, 0, false),
        new Mark(DIAGONAL, -DIAGONAL, 45, false),
        new Mark(16D, 0D, 90, false),
        new Mark(DIAGONAL, DIAGONAL, 135, false),
        new Mark(0D, 16D, 180, false),
        new Mark(-DIAGONAL, DIAGONAL, 225, false),
        new Mark(-16D, 0D, 270, false),
        new Mark(-DIAGONAL, -DIAGONAL, 315, false)
    );

    private HorologiumGearMarks() { }

    /**
     * @param point 対象位置
     * @param center 時計盤中心
     * @return 対象へ最も近い歯車印
     */
    static Mark nearest(Location point, Location center) {
        double x = point.getX() - center.getX();
        double z = point.getZ() - center.getZ();
        Mark nearest = ALL.getFirst();
        double best = Double.POSITIVE_INFINITY;
        for (Mark mark : ALL) {
            double squared = mark.distanceSquared(x, z);
            if (squared < best) {
                best = squared;
                nearest = mark;
            }
        }
        return nearest;
    }

    /** 歯車印の中心差分と方位。 */
    record Mark(double x, double z, int bearing, boolean inner) {
        /**
         * @param center 時計盤中心
         * @return 歯車印中心の地点
         */
        Location at(Location center) {
            return center.clone().add(x, 0D, z);
        }

        /**
         * @param x 中心からのX差分
         * @param z 中心からのZ差分
         * @return 歯車印中心までの平面二乗距離
         */
        double distanceSquared(double x, double z) {
            double dx = x - this.x;
            double dz = z - this.z;
            return dx * dx + dz * dz;
        }

        /**
         * @param point 対象位置
         * @param center 時計盤中心
         * @return 対象から歯車印中心までの平面距離
         */
        double distance(Location point, Location center) {
            return Math.sqrt(distanceSquared(point.getX() - center.getX(), point.getZ() - center.getZ()));
        }

        /**
         * 床を塗るセルと成功判定に同じ規則を使います。
         * @param cellX 時計盤中心の床ブロックからのXセル差分
         * @param cellZ 時計盤中心の床ブロックからのZセル差分
         * @return この印として塗る床セルならtrue
         */
        boolean paints(int cellX, int cellZ) {
            return distanceSquared(cellX, cellZ) <= RADIUS * RADIUS;
        }

        /**
         * 足元の床セル全体を判定し、セル中心から外れた角でも塗り床と一致させます。
         * @param point 判定するプレイヤー位置
         * @param center 時計盤中心
         * @return 対象の足元がこの印の塗りセル内ならtrue
         */
        boolean containsPaintedFloor(Location point, Location center) {
            if (point.getWorld() != center.getWorld()) return false;
            int x = point.getBlockX() - center.getBlockX();
            int z = point.getBlockZ() - center.getBlockZ();
            return paints(x, z);
        }

        /**
         * 塗りセルの外周辺だけを描画点にし、斜め印でも判定と輪郭を一致させます。
         * @param center 時計盤中心
         * @return 外周の各ブロック辺の中央点
         */
        List<Location> outline(Location center) {
            List<Location> points = new ArrayList<>(20);
            int minX = (int) Math.floor(x - RADIUS) - 1;
            int maxX = (int) Math.ceil(x + RADIUS) + 1;
            int minZ = (int) Math.floor(z - RADIUS) - 1;
            int maxZ = (int) Math.ceil(z + RADIUS) + 1;
            for (int cellX = minX; cellX <= maxX; cellX++) {
                for (int cellZ = minZ; cellZ <= maxZ; cellZ++) {
                    if (!paints(cellX, cellZ)) continue;
                    if (!paints(cellX - 1, cellZ)) points.add(center.clone().add(cellX - 0.5D, 0.35D, cellZ));
                    if (!paints(cellX + 1, cellZ)) points.add(center.clone().add(cellX + 0.5D, 0.35D, cellZ));
                    if (!paints(cellX, cellZ - 1)) points.add(center.clone().add(cellX, 0.35D, cellZ - 0.5D));
                    if (!paints(cellX, cellZ + 1)) points.add(center.clone().add(cellX, 0.35D, cellZ + 0.5D));
                }
            }
            return points;
        }
    }
}
