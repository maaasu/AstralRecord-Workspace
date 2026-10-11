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
    static final double FIELD_RADIUS = 48.0D;
    static final double ENGAGEMENT_RADIUS = 32.0D;
    static final double MOON_RADIUS = 12.0D;
    static final double MARK_RADIUS = 5.0D;
    static final double PASSAGE_HALF_WIDTH = 6.0D;
    static final double SAFE_HINT_RADIUS = 1.5D;
    private static final double SAFE_MARGIN_RADIUS = SAFE_HINT_RADIUS + 0.35D;

    private SeleneaArena() { }

    /** 各攻撃の水平形状。描画時にもこの判定を使用します。 */
    enum Shape { NONE, FORK, MARKS, INNER, OUTER, PASSAGE, RETURN }

    /**
     * 凍結した予兆と同じ領域かを判定します。ジャンプでは避けず、水平方向へ退避します。
     * @param shape 技の形状
     * @param center 戦闘開始地点
     * @param direction 予兆開始時に固定した単位方向
     * @param marks 固定した落款の中心
     * @param radius 詠唱開始時に固定した攻撃半径
     * @param point 判定対象の足元
     * @return 同一world・高さ±3mで危険領域内ならtrue
     */
    static boolean contains(Shape shape, Location center, Vector direction, List<Location> marks, double radius, Location point) {
        if (!sameFloor(point, center)) return false;
        double distance = squared(point, center);
        if (shape == Shape.RETURN) return distance > radius * radius
            && distance <= ENGAGEMENT_RADIUS * ENGAGEMENT_RADIUS;
        if (distance > radius * radius) return false;
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
        return inArena(point, center, RADIUS);
    }

    /** @param point 判定対象の足元 @param center 会場中心 @param radius 凍結した半径
     * @return 同じ戦闘階の指定半径内ならtrue */
    static boolean inArena(Location point, Location center, double radius) {
        return sameFloor(point, center) && squared(point, center) <= radius * radius;
    }

    /**
     * 専用フィールドで中央、四方向の足場、高跳びを含む参加領域を判定します。
     * @param point 参加者の現在位置
     * @param center ボス出現位置
     * @return 同一world・水平90m以内・高さ差-8〜40mならtrue
     */
    static boolean inFieldArena(Location point, Location center) {
        double height = point.getY() - center.getY();
        return point.getWorld() == center.getWorld() && height >= -8.0D && height <= 40.0D
            && squared(point, center) <= 90.0D * 90.0D;
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
     * @param radius 凍結した攻撃半径
     * @return 退避先。床や経路が確保できない場合null
     */
    static @Nullable Location safeSpot(Location from, Location center, Shape shape, Vector direction,
                                       List<Location> marks, long warningTicks, double radius) {
        Location start = ground(from);
        if (start == null) return null;
        double maxDistance = Math.min(radius * 2.0D, warningTicks / 20.0D * 3.0D);
        for (double distance = 0; distance <= maxDistance; distance += 1.0D) {
            for (int angle = 0; angle < (distance == 0 ? 1 : 24); angle++) {
                double radians = angle * Math.PI / 12.0D;
                Location candidate = ground(start.clone().add(Math.cos(radians) * distance, 0,
                    Math.sin(radians) * distance));
                if (candidate == null || !safeCircle(candidate, center, shape, direction, marks, radius)) continue;
                if (walkablePath(start, candidate)) return candidate;
            }
        }
        return null;
    }

    /** 表示半径1.5mの安全輪に体幅0.35mの余白を加え、内周と外周の床・危険領域を検査します。 */
    private static boolean safeCircle(Location point, Location center, Shape shape, Vector direction, List<Location> marks,
                                      double radius) {
        if (!inArena(point, center, radius) || contains(shape, center, direction, marks, radius, point)
            || !walkable(point)) return false;
        for (double ringRadius : new double[]{SAFE_MARGIN_RADIUS / 2.0D, SAFE_MARGIN_RADIUS}) {
            for (int i = 0; i < 16; i++) {
                Location edge = point.clone().add(Math.cos(i * Math.PI / 8) * ringRadius,
                    0, Math.sin(i * Math.PI / 8) * ringRadius);
                if (!inArena(edge, center, radius) || contains(shape, center, direction, marks, radius, edge)
                    || !walkable(edge)) return false;
            }
        }
        return true;
    }

    /** chunkの強制読込を避けます。 */
    static boolean loaded(Location point) {
        return point.getWorld() != null && point.getWorld().isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4);
    }
}
