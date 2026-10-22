package io.github.maaasu.astralRecord.feature.boss.service;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 星環の観測台の床・歩行経路と、表示と判定で共有する安全形状を定義します。 */
final class AstraiosArena {
    static final double RADIUS = 23.0D;
    static final double BEACON_DISTANCE = 12.0D;
    static final double SAFE_RADIUS = 3.5D;

    private AstraiosArena() { }

    /** @return 同一ワールドの観測台と戦闘階にいる場合true */
    static boolean contains(Location point, Location center) {
        return point.getWorld() == center.getWorld() && Math.abs(point.getY() - center.getY()) <= 3.0D
            && GreatrootArena.squared(point, center) <= RADIUS * RADIUS;
    }

    /** @return 北・東・南・西の順に並ぶ観測点。入力地点は変更しません */
    static Location beacon(Location center, int index) {
        return switch (Math.floorMod(index, 4)) {
            case 0 -> center.clone().add(0, 0, -BEACON_DISTANCE);
            case 1 -> center.clone().add(BEACON_DISTANCE, 0, 0);
            case 2 -> center.clone().add(0, 0, BEACON_DISTANCE);
            default -> center.clone().add(-BEACON_DISTANCE, 0, 0);
        };
    }

    /**
     * 直線または中央経由の歩行距離を返します。穴・壁・別階・未読込chunkは通過しません。
     * @return 到達できない場合は正の無限大
     */
    static double walkingDistance(Location from, Location to, Location center) {
        Location start = GreatrootArena.ground(from), end = GreatrootArena.ground(to);
        if (start == null || end == null || !contains(start, center) || !contains(end, center))
            return Double.POSITIVE_INFINITY;
        if (segment(start, end)) return Math.sqrt(GreatrootArena.squared(start, end));
        Location middle = GreatrootArena.ground(center);
        return middle != null && segment(start, middle) && segment(middle, end)
            ? Math.sqrt(GreatrootArena.squared(start, middle)) + Math.sqrt(GreatrootArena.squared(middle, end))
            : Double.POSITIVE_INFINITY;
    }

    /** @return 共通の20m以内の検査へ分割して、長距離でも体幅と段差を検証した場合true */
    private static boolean segment(Location from, Location to) {
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(GreatrootArena.squared(from, to)) / 12.0D));
        Location previous = from;
        for (int i = 1; i <= steps; i++) {
            Location next = from.clone().add(to.toVector().subtract(from.toVector()).multiply(i / (double) steps));
            next = GreatrootArena.ground(next);
            if (next == null || !GreatrootArena.path(previous, next)) return false;
            previous = next;
        }
        return true;
    }

    /** @return 体幅の余白を含め、最寄りの安全地点までの検証済み歩行距離 */
    static double escapeDistance(Location from, Zone zone) {
        double best = Double.POSITIVE_INFINITY;
        for (Location candidate : zone.destinations()) {
            double lowerBound = Math.sqrt(GreatrootArena.squared(from, candidate));
            if (lowerBound >= best) continue;
            boolean margin = true;
            for (int i = 0; i < 8; i++) {
                Location rim = candidate.clone().add(Math.cos(i * Math.PI / 4) * 0.8D, 0,
                    Math.sin(i * Math.PI / 4) * 0.8D);
                Location floor = GreatrootArena.ground(rim);
                if (floor == null || !zone.safe(floor)) { margin = false; break; }
            }
            if (margin) best = Math.min(best, walkingDistance(from, candidate, zone.center));
        }
        return best;
    }

    enum Shape { REFUGE, ALIGNMENT, INNER, OUTER, HORIZONTAL, VERTICAL, QUADRANTS, CROSS, MARKS }

    /** 安全判定・地表描画・歩行先探索を同じ形状から計算します。 */
    record Zone(@NotNull Shape shape, @NotNull Location center, int direction, @NotNull List<Location> marks) {
        Zone {
            center = center.clone();
            marks = marks.stream().map(Location::clone).toList();
        }

        /** @return その瞬間の判定でダメージを受けない会場内地点ならtrue */
        boolean safe(Location point) {
            if (!contains(point, center)) return false;
            double x = point.getX() - center.getX(), z = point.getZ() - center.getZ();
            double distance = x * x + z * z;
            return switch (shape) {
                case REFUGE, ALIGNMENT -> GreatrootArena.squared(point, beacon(center, direction)) <= SAFE_RADIUS * SAFE_RADIUS;
                case INNER -> distance <= 5.0D * 5.0D;
                case OUTER -> distance >= 10.0D * 10.0D;
                case HORIZONTAL -> Math.abs(z) <= 2.8D && Math.abs(x) >= 7.0D;
                case VERTICAL -> Math.abs(x) <= 2.8D && Math.abs(z) >= 7.0D;
                case QUADRANTS -> (direction % 2 == 0 ? x * z >= 0 : x * z <= 0)
                    && Math.abs(x) >= 1.2D && Math.abs(z) >= 1.2D;
                case CROSS -> Math.abs(x) > 2.5D && Math.abs(z) > 2.5D;
                case MARKS -> marks.stream().noneMatch(mark -> GreatrootArena.squared(mark, point) <= 3.0D * 3.0D);
            };
        }

        /** @return 安全形状内の候補。現在地点と同じ固定位置を優先し続けない有限探索です */
        List<Location> destinations() {
            if (shape == Shape.REFUGE || shape == Shape.ALIGNMENT) return List.of(beacon(center, direction));
            List<Location> points = new ArrayList<>();
            if (safe(center)) points.add(center.clone());
            for (double radius : new double[] {4, 7, 12, 17, 20}) {
                for (int i = 0; i < 16; i++) {
                    Location point = center.clone().add(Math.cos(i * Math.PI / 8) * radius, 0,
                        Math.sin(i * Math.PI / 8) * radius);
                    if (safe(point)) points.add(point);
                }
            }
            return points;
        }
    }
}
