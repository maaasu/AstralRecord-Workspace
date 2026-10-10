package io.github.maaasu.astralRecord.feature.boss.service;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** セレネアの予兆と命中で共有する形状・歩行可能な退避先の判定です。 */
final class SeleneaArena {
    static final double RADIUS = 18.0D;
    static final double ENGAGEMENT_RADIUS = 32.0D;
    static final double MOON_RADIUS = 6.0D;
    static final double MARK_RADIUS = 2.5D;
    static final double PASSAGE_HALF_WIDTH = 3.0D;

    private SeleneaArena() { }

    /** 各攻撃の水平形状。描画時にもこの判定を使用します。 */
    enum Shape { NONE, FORK, MARKS, INNER, OUTER, PASSAGE, RETURN }

    /**
     * 凍結した予兆と同じ領域かを判定します。ジャンプでは避けず、水平方向へ退避します。
     * @param shape 技の形状
     * @param center 戦闘開始地点
     * @param direction 予兆開始時に固定した単位方向
     * @param marks 固定した落款の中心
     * @param point 判定対象の足元
     * @return 同一world・高さ±3mで危険領域内ならtrue
     */
    static boolean contains(Shape shape, Location center, Vector direction, List<Location> marks, Location point) {
        if (!sameFloor(point, center)) return false;
        double distance = squared(point, center);
        if (shape == Shape.RETURN) return distance > RADIUS * RADIUS
            && distance <= ENGAGEMENT_RADIUS * ENGAGEMENT_RADIUS;
        if (distance > RADIUS * RADIUS) return false;
        double x = point.getX() - center.getX(), z = point.getZ() - center.getZ();
        return switch (shape) {
            case FORK -> {
                boolean hit = false;
                for (int ray = -1; ray <= 1; ray++) {
                    Vector aim = direction.clone().rotateAroundY(ray * Math.PI / 6.0D);
                    double along = x * aim.getX() + z * aim.getZ();
                    double across = Math.abs(x * aim.getZ() - z * aim.getX());
                    if (along >= -0.5D && across <= 1.25D) hit = true;
                }
                yield hit;
            }
            case MARKS -> marks.stream().anyMatch(mark -> squared(point, mark) <= MARK_RADIUS * MARK_RADIUS);
            case INNER -> distance <= MOON_RADIUS * MOON_RADIUS;
            case OUTER -> distance > MOON_RADIUS * MOON_RADIUS;
            case PASSAGE -> Math.abs(x * direction.getZ() - z * direction.getX()) > PASSAGE_HALF_WIDTH;
            default -> false;
        };
    }

    /** @return 同じ戦闘階の18m円内ならtrue */
    static boolean inArena(Location point, Location center) {
        return sameFloor(point, center) && squared(point, center) <= RADIUS * RADIUS;
    }

    /** @return 同一worldかつ出現高さとの差が3m以内ならtrue */
    static boolean sameFloor(Location point, Location center) {
        return point.getWorld() == center.getWorld() && Math.abs(point.getY() - center.getY()) <= 3.0D;
    }

    /** @return 二地点の水平距離の二乗 */
    static double squared(Location a, Location b) {
        double x = a.getX() - b.getX(), z = a.getZ() - b.getZ();
        return x * x + z * z;
    }

    /** @return 水平単位方向。完全に重なるときは東 */
    static Vector direction(Location from, Location to) {
        Vector delta = to.toVector().subtract(from.toVector()).setY(0.0D);
        return delta.lengthSquared() < 0.001D ? new Vector(1, 0, 0) : delta.normalize();
    }

    /** @return 未ロードchunkに触れず照射可能ならtrue */
    static boolean visible(Location from, Location to) {
        if (from.getWorld() != to.getWorld() || !loaded(from) || !loaded(to)) return false;
        Vector delta = to.toVector().subtract(from.toVector());
        int checks = Math.max(1, (int) Math.ceil(delta.length()));
        for (int i = 0; i <= checks; i++) {
            if (!loaded(from.clone().add(delta.clone().multiply(i / (double) checks)))) return false;
        }
        return delta.lengthSquared() < 0.001D || from.getWorld().rayTraceBlocks(from,
            delta.clone().normalize(), delta.length(), FluidCollisionMode.NEVER, true) == null;
    }

    /** 足元付近だけを走査し、上階や液体・危険床を採用しません。 */
    static @Nullable Location ground(Location point) {
        if (!loaded(point)) return null;
        World world = point.getWorld();
        for (int y = Math.min(point.getBlockY(), world.getMaxHeight() - 1);
             y >= Math.max(world.getMinHeight(), point.getBlockY() - 3); y--) {
            Block floor = world.getBlockAt(point.getBlockX(), y, point.getBlockZ());
            if (!floor.getType().isSolid() || floor.isPassable()) continue;
            double top = floor.getBoundingBox().getMaxY();
            if (top > point.getY() + 0.05D || top < point.getY() - 2.5D) continue;
            Location result = point.clone();
            result.setY(top);
            if (walkable(result)) return result;
        }
        return null;
    }

    /** 穴、接触ダメージ、頭上2mの障害物を除外します。 */
    private static boolean walkable(Location point) {
        if (!loaded(point)) return false;
        Block floor = point.clone().add(0, -0.05D, 0).getBlock();
        Material material = floor.getType();
        if (!material.isSolid() || floor.isLiquid() || material == Material.MAGMA_BLOCK
            || material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE || material == Material.CACTUS) return false;
        for (double y = 0.1D; y <= 1.9D; y += 0.6D) {
            Block block = point.clone().add(0, y, 0).getBlock();
            if (!block.isPassable() || block.isLiquid() || block.getType() == Material.FIRE
                || block.getType() == Material.SOUL_FIRE || block.getType() == Material.SWEET_BERRY_BUSH) return false;
        }
        return true;
    }

    /** 床の連続性とプレイヤーの体幅を0.5m間隔で検査します。 */
    static boolean walkablePath(Location from, Location to) {
        Vector delta = to.toVector().subtract(from.toVector()).setY(0);
        int steps = Math.max(1, (int) Math.ceil(delta.length() / 0.5D));
        Vector side = delta.lengthSquared() < 0.001D ? new Vector()
            : new Vector(-delta.getZ(), 0, delta.getX()).normalize().multiply(0.35D);
        double lastY = from.getY();
        for (int i = 0; i <= steps; i++) {
            Location point = from.clone().add(delta.clone().multiply(i / (double) steps));
            point.setY(lastY);
            Location floor = ground(point);
            if (floor == null || Math.abs(floor.getY() - lastY) > 0.6D
                || !walkable(floor.clone().add(side)) || !walkable(floor.clone().subtract(side))) return false;
            lastY = floor.getY();
        }
        return Math.abs(lastY - to.getY()) <= 0.6D;
    }

    /**
     * 予兆時間内に歩いて届く、体幅分の余白を持つ退避先を探します。
     * @param from プレイヤーの足元（ジャンプ中は床へ投影）
     * @param center 会場中心
     * @param shape 危険形状
     * @param direction 固定方向
     * @param marks 固定落款
     * @param warningTicks 歩行可能距離の計算に使う予兆時間
     * @return 退避先。床や経路が確保できない場合null
     */
    static @Nullable Location safeSpot(Location from, Location center, Shape shape, Vector direction,
                                       List<Location> marks, long warningTicks) {
        Location start = ground(from);
        if (start == null) return null;
        double maxDistance = Math.min(18.0D, warningTicks / 20.0D * 3.0D);
        for (double distance = 0; distance <= maxDistance; distance += 1.0D) {
            for (int angle = 0; angle < (distance == 0 ? 1 : 24); angle++) {
                double radians = angle * Math.PI / 12.0D;
                Location candidate = ground(start.clone().add(Math.cos(radians) * distance, 0,
                    Math.sin(radians) * distance));
                if (candidate == null || !safeCircle(candidate, center, shape, direction, marks)) continue;
                if (walkablePath(start, candidate)) return candidate;
            }
        }
        return null;
    }

    /** 安全輪の全周まで体幅と同一の床を保証します。 */
    private static boolean safeCircle(Location point, Location center, Shape shape, Vector direction, List<Location> marks) {
        for (int i = -1; i < 8; i++) {
            Location edge = i < 0 ? point : point.clone().add(Math.cos(i * Math.PI / 4) * 0.65D,
                0, Math.sin(i * Math.PI / 4) * 0.65D);
            if (!inArena(edge, center) || contains(shape, center, direction, marks, edge) || !walkable(edge)) return false;
        }
        return true;
    }

    /** chunkの強制読込を避けます。 */
    static boolean loaded(Location point) {
        return point.getWorld() != null && point.getWorld().isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4);
    }
}
