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
    static final double ULTIMATE_PASSAGE_HALF_WIDTH = 2.75D;
    static final double SAFE_HINT_RADIUS = 1.5D;
    private static final double SAFE_MARGIN_RADIUS = SAFE_HINT_RADIUS + 0.35D;

    private SeleneaArena() { }

    /** 各攻撃の水平形状。描画時にもこの判定を使用します。 */
    enum Shape { NONE, FORK, MARKS, INNER, OUTER, PASSAGE, ULTIMATE_PASSAGE, RETURN }

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
        return contains(shape, center, direction, marks, radius, point, false);
    }

    /**
     * 専用フィールドでは高跳び中も水平の危険形状で判定します。
     * @param shape 固定形状 @param center 会場中心 @param direction 固定方向 @param marks 落款中心
     * @param radius 攻撃半径 @param point 足元 @param field 専用フィールドか
     * @return 専用では直下が戦闘階に属し、参加高さと水平形状を満たす場合true
     */
    static boolean contains(Shape shape, Location center, Vector direction, List<Location> marks,
                            double radius, Location point, boolean field) {
        if (field ? !inAttackArena(point, center, radius, true) : !sameFloor(point, center)) return false;
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
            case PASSAGE, ULTIMATE_PASSAGE -> Math.abs(x * direction.getZ() - z * direction.getX())
                > (shape == Shape.ULTIMATE_PASSAGE ? ULTIMATE_PASSAGE_HALF_WIDTH : PASSAGE_HALF_WIDTH);
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
     * 通常呪文とボスへの攻撃を同じ中央の参加範囲へ限定します。
     * @param point 足元 @param center 会場中心 @param radius 攻撃半径 @param field 専用フィールドか
     * @return 専用では参加高さ内の水平円かつ直下の床が中心の高さ±0.6m、通常では足元が高さ±3mの円内ならtrue
     */
    static boolean inAttackArena(Location point, Location center, double radius, boolean field) {
        if (squared(point, center) > radius * radius) return false;
        if (!field) return sameFloor(point, center);
        Location floor = ground(point, center, true);
        return floor != null && battleFloor(floor, center);
    }

    /** 投影済みの床だけを中央戦闘階の段差0.6m以内で判定し、孤立した下段床を除外します。 */
    private static boolean battleFloor(Location floor, Location center) {
        return floor.getWorld() == center.getWorld() && Math.abs(floor.getY() - center.getY()) <= 0.6D;
    }

    /**
     * 専用フィールドで中央、四方向の足場、高跳びを含む参加領域を判定します。
     * @param point 参加者の現在位置
     * @param center ボス出現位置
     * @return 同一world・水平90m以内・高さ差-16〜40mならtrue。下段の跳躍床も参加範囲に含めます
     */
    static boolean inFieldArena(Location point, Location center) {
        double height = point.getY() - center.getY();
        return point.getWorld() == center.getWorld() && height >= -16.0D && height <= 40.0D
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

    /**
     * 専用フィールドの空中の足元を参加高さ内の直下の安全な床へ投影します。
     * @param point 足元 @param center 会場中心 @param field 専用フィールドか
     * @return 歩行可能な床。参加範囲外、未ロード、床なしではnull
     */
    static @Nullable Location ground(Location point, Location center, boolean field) {
        if (!field) return ground(point);
        if (!inFieldArena(point, center) || !loaded(point)) return null;
        World world = point.getWorld();
        int minimum = Math.max(world.getMinHeight(), (int) Math.floor(center.getY() - 16.0D) - 1);
        for (int y = Math.min(point.getBlockY(), world.getMaxHeight() - 1); y >= minimum; y--) {
            Block floor = world.getBlockAt(point.getBlockX(), y, point.getBlockZ());
            if (!floor.getType().isSolid() || floor.isPassable()) continue;
            double top = floor.getBoundingBox().getMaxY();
            if (top > point.getY() + 0.05D) return null;
            if (top < center.getY() - 16.0D || top > center.getY() + 40.0D) continue;
            Location result = point.clone();
            result.setY(top);
            // 最初の固体床が危険・閉塞なら、床を貫通して下の安全床を採用しません。
            return walkable(result) ? result : null;
        }
        return null;
    }

    /**
     * クライアント申告の接地flagに依存せず、安全な床面と足元の高さから接地を確認します。
     * @param point 足元 @return 足元の直下0.1m以内に安全な床がある場合true
     */
    static boolean standingOnGround(Location point) {
        Location floor = ground(point);
        return floor != null && Math.abs(point.getY() - floor.getY()) <= 0.1D;
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
        return safeSpot(from, center, shape, direction, marks, warningTicks, radius, false);
    }

    /**
     * 空中参加者も専用フィールドの床へ投影して、同じ水平形状から退避先を探します。
     * @param from 足元 @param center 会場中心 @param shape 形状 @param direction 固定方向 @param marks 落款
     * @param warningTicks 退避時間 @param radius 攻撃半径 @param field 専用フィールドか
     * @return 体幅を含む輪と歩行経路が安全な床。成立しなければnull
     */
    static @Nullable Location safeSpot(Location from, Location center, Shape shape, Vector direction,
                                       List<Location> marks, long warningTicks, double radius, boolean field) {
        Location start = ground(from, center, field);
        if (start == null) return null;
        double maxDistance = Math.min(radius * 2.0D, warningTicks / 20.0D * 3.0D);
        for (double distance = 0; distance <= maxDistance; distance += 1.0D) {
            for (int angle = 0; angle < (distance == 0 ? 1 : 24); angle++) {
                double radians = angle * Math.PI / 12.0D;
                Location candidate = ground(start.clone().add(Math.cos(radians) * distance, 0,
                    Math.sin(radians) * distance));
                if (candidate == null || !safeCircle(candidate, center, shape, direction, marks, radius, field)) continue;
                if (walkablePath(start, candidate)) return candidate;
            }
        }
        return null;
    }

    /** 表示半径1.5mの安全輪に体幅0.35mの余白を加え、内周と外周の床・危険領域を検査します。 */
    private static boolean safeCircle(Location point, Location center, Shape shape, Vector direction, List<Location> marks,
                                      double radius, boolean field) {
        if (!inAttackArena(point, center, radius, field) || contains(shape, center, direction, marks, radius, point, field)
            || !walkable(point)) return false;
        for (double ringRadius : new double[]{SAFE_MARGIN_RADIUS / 2.0D, SAFE_MARGIN_RADIUS}) {
            for (int i = 0; i < 16; i++) {
                Location edge = point.clone().add(Math.cos(i * Math.PI / 8) * ringRadius,
                    0, Math.sin(i * Math.PI / 8) * ringRadius);
                if (!inAttackArena(edge, center, radius, field) || contains(shape, center, direction, marks, radius, edge, field)
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
