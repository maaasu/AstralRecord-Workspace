package io.github.maaasu.astralRecord.feature.boss.service;

import org.bukkit.Location;

import java.util.List;

/**
 * 影輪の天球儀の床・遮蔽柱・封印板・守護像の位置を、ボス召喚位置（闘技場中心）からの相対値で扱います。
 *
 * <p>座標はブロック中心を基準にします。+Xが東、+Zが南で、角度は東を0度として南回りに数えます。
 * 方位の表示は北を0度とした時計回りに変換します。</p>
 */
final class NyxeronArena {
    /** 平らな床の半径。内壁は40.5から始まります。 */
    static final double FLOOR_RADIUS = 40.5D;
    /** 参加資格と攻撃許可に使う半径。壁際の当たり判定の余白を含みます。 */
    static final double ARENA_RADIUS = 41.0D;
    /** 歩行面からの高さ許容。遮蔽柱の上（+8）へは登れないため床面だけを対象にします。 */
    static final double ARENA_HEIGHT = 6.0D;
    /** 床に描かれた案内環の半径。帯の境界にも使います。 */
    static final double[] RING_RADII = {8.0D, 16.0D, 24.0D, 32.0D};
    /** 遮蔽柱の中心（3×3）。 */
    static final List<double[]> PYLONS = List.of(
        new double[] {14.0D, -14.0D}, new double[] {-14.0D, -14.0D},
        new double[] {-14.0D, 14.0D}, new double[] {14.0D, 14.0D});
    /** 遮蔽柱の当たり判定の半幅。3×3柱の1.5に、体の幅の余白を加えます。 */
    static final double PYLON_HALF = 1.75D;
    /** 封印板（東から南回りに45度ごと）。中心はブロック中心です。 */
    static final List<double[]> SEALS = List.of(
        new double[] {36.0D, 0.0D}, new double[] {25.0D, 25.0D}, new double[] {0.0D, 36.0D},
        new double[] {-25.0D, 25.0D}, new double[] {-36.0D, 0.0D}, new double[] {-25.0D, -25.0D},
        new double[] {0.0D, -36.0D}, new double[] {25.0D, -25.0D});
    /** 封印板の踏み判定の半径（金の縁の内側）。 */
    static final double SEAL_RADIUS = 2.4D;
    /** 守護像のある方位（南の門を除く7方向）。度数は東0・南90。 */
    static final int[] STATUE_ANGLES = {0, 45, 135, 180, 225, 270, 315};
    /** 天球儀の核は歩行面の46ブロック上にあります。 */
    static final double SPHERE_HEIGHT = 46.0D;

    private NyxeronArena() {
    }

    /** @param point 判定位置 @param center 闘技場中心 @return 同じworldの床面上で、壁の内側ならtrue */
    static boolean inside(Location point, Location center) {
        if (point.getWorld() != center.getWorld() || Math.abs(point.getY() - center.getY()) > ARENA_HEIGHT) return false;
        return horizontalDistance(point, center) <= ARENA_RADIUS;
    }

    /** @param point 判定位置 @param center 中心 @return 平面距離 */
    static double horizontalDistance(Location point, Location center) {
        double dx = point.getX() - center.getX();
        double dz = point.getZ() - center.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 帯の番号から内外の半径を返します。0は中心円、4は外周の帯です。
     * @param band 0～4 @return {内側, 外側}
     */
    static double[] band(int band) {
        double inner = band == 0 ? 0.0D : RING_RADII[band - 1];
        double outer = band >= RING_RADII.length ? FLOOR_RADIUS : RING_RADII[band];
        return new double[] {inner, outer};
    }

    /**
     * 帯の危険判定。案内環の線上（±0.35）は両側の帯に含めます。
     * @param distance 中心からの平面距離 @param band 0～4 @return 帯の中か線上ならtrue
     */
    static boolean inBand(double distance, int band) {
        double[] range = band(band);
        double low = band == 0 ? -1.0D : range[0] - 0.35D;
        return distance >= low && distance < range[1] + 0.35D;
    }

    /**
     * 中心から角度方向へ伸びる光条に入っているかを判定します。
     * @param point 判定位置 @param center 中心 @param angleDegrees 東0・南90の角度
     * @param halfWidth 光条の半幅 @return 光条の範囲ならtrue
     */
    static boolean onSpoke(Location point, Location center, double angleDegrees, double halfWidth) {
        double dx = point.getX() - center.getX();
        double dz = point.getZ() - center.getZ();
        double radians = Math.toRadians(angleDegrees);
        double ux = Math.cos(radians);
        double uz = Math.sin(radians);
        double along = dx * ux + dz * uz;
        double across = Math.abs(dx * uz - dz * ux);
        return along >= -halfWidth && along <= ARENA_RADIUS && across <= halfWidth;
    }

    /**
     * 封印板から見た視線が遮蔽柱で遮られているかを判定します。
     * @param origin 視線の起点（封印板の中心） @param point 参加者の位置 @param center 闘技場中心
     * @return いずれかの柱の正方形と線分が交差すればtrue
     */
    static boolean shadowed(Location origin, Location point, Location center) {
        double ox = origin.getX() - center.getX();
        double oz = origin.getZ() - center.getZ();
        double px = point.getX() - center.getX();
        double pz = point.getZ() - center.getZ();
        for (double[] pylon : PYLONS) {
            if (segmentHitsSquare(ox, oz, px, pz, pylon[0], pylon[1], PYLON_HALF)) return true;
        }
        return false;
    }

    /** Liang-Barskyで、線分が軸平行の正方形と交差するかを求めます。 */
    static boolean segmentHitsSquare(double x0, double z0, double x1, double z1,
                                     double cx, double cz, double half) {
        double dx = x1 - x0;
        double dz = z1 - z0;
        double t0 = 0.0D;
        double t1 = 1.0D;
        double[] p = {-dx, dx, -dz, dz};
        double[] q = {x0 - (cx - half), (cx + half) - x0, z0 - (cz - half), (cz + half) - z0};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0.0D) {
                if (q[i] < 0.0D) return false;
                continue;
            }
            double r = q[i] / p[i];
            if (p[i] < 0.0D) {
                if (r > t1) return false;
                if (r > t0) t0 = r;
            } else {
                if (r < t0) return false;
                if (r < t1) t1 = r;
            }
        }
        return true;
    }

    /** @param center 中心 @param index 封印板番号0～7 @return 封印板の中心（床面） */
    static Location seal(Location center, int index) {
        double[] offset = SEALS.get(Math.floorMod(index, SEALS.size()));
        return center.clone().add(offset[0], 0.0D, offset[1]);
    }

    /** @param center 中心 @param index 柱番号0～3 @return 柱の中心（床面） */
    static Location pylon(Location center, int index) {
        double[] offset = PYLONS.get(index);
        return center.clone().add(offset[0], 0.0D, offset[1]);
    }

    /** @param point 封印板の判定位置 @param seal 封印板中心 @return 同じ床面の踏み判定内ならtrue */
    static boolean onSeal(Location point, Location seal) {
        if (point.getWorld() != seal.getWorld() || Math.abs(point.getY() - seal.getY()) > 2.0D) return false;
        double dx = point.getX() - seal.getX();
        double dz = point.getZ() - seal.getZ();
        return dx * dx + dz * dz <= SEAL_RADIUS * SEAL_RADIUS;
    }

    /** @param angleDegrees 東0・南90の角度 @return 北0・時計回りの方位 */
    static int bearing(double angleDegrees) {
        return Math.floorMod((int) Math.round(angleDegrees + 90.0D), 360);
    }

    /** @param index 封印板番号 @return 北0・時計回りの方位 */
    static int sealBearing(int index) {
        return bearing(Math.floorMod(index, SEALS.size()) * 45.0D);
    }
}
