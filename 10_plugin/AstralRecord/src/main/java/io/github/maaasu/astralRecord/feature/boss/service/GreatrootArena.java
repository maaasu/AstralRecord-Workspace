package io.github.maaasu.astralRecord.feature.boss.service;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** 大樹の根洞の有限会場と、穴・壁・別階を除外する足場探索を担当します。 */
final class GreatrootArena {
    static final double RADIUS = 14.0D;
    private static final double HEIGHT_DELTA = 3.0D;

    private GreatrootArena() { }

    /** @return 会場の同一world・戦闘階・水平半径内ならtrue */
    static boolean contains(@NotNull Location point, @NotNull Location center) {
        return point.getWorld() == center.getWorld() && Math.abs(point.getY() - center.getY()) <= HEIGHT_DELTA
            && squared(point, center) <= RADIUS * RADIUS;
    }

    /** @return 同一worldにある二地点の水平距離の二乗 */
    static double squared(@NotNull Location first, @NotNull Location second) {
        double x = first.getX() - second.getX(), z = first.getZ() - second.getZ();
        return x * x + z * z;
    }

    /** 未読込chunkを生成せず、足元付近の支持面へ有限探索で投影します。 */
    static @Nullable Location ground(@NotNull Location point) {
        if (!loaded(point)) return null;
        World world = point.getWorld();
        for (int y = Math.min(world.getMaxHeight() - 3, point.getBlockY() + 1);
             y >= Math.max(world.getMinHeight(), point.getBlockY() - 3); y--) {
            Block floor = world.getBlockAt(point.getBlockX(), y, point.getBlockZ());
            if (!floor.getType().isSolid() || floor.isPassable() || floor.isLiquid() || dangerous(floor.getType())) continue;
            double top = floor.getBoundingBox().getMaxY();
            if (top < point.getY() - 2.0D || top > point.getY() + 1.0D) continue;
            Location result = point.clone();
            result.setY(top);
            if (walkable(result)) return result;
        }
        return null;
    }

    /** @return 読込済み支持面と頭上の空間がある、安全な歩行地点ならtrue */
    private static boolean walkable(@NotNull Location point) {
        if (!loaded(point)) return false;
        Block floor = point.clone().add(0, -0.02D, 0).getBlock();
        if (!floor.getType().isSolid() || floor.isPassable() || floor.isLiquid() || dangerous(floor.getType())
            || Math.abs(floor.getBoundingBox().getMaxY() - point.getY()) > 0.12D) return false;
        for (double y = 0.1D; y <= 1.9D; y += 0.6D) {
            Block space = point.clone().add(0, y, 0).getBlock();
            if (!space.isPassable() || space.isLiquid() || dangerous(space.getType())) return false;
        }
        return true;
    }

    /** @return 火・接触ダメージ・沈み込む床ならtrue */
    private static boolean dangerous(@NotNull Material material) {
        return material == Material.MAGMA_BLOCK || material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE
            || material == Material.LAVA || material == Material.FIRE || material == Material.SOUL_FIRE
            || material == Material.CACTUS || material == Material.SWEET_BERRY_BUSH
            || material == Material.WITHER_ROSE || material == Material.POWDER_SNOW;
    }

    /** @return 足元・体幅・段差を0.5m間隔で確認した短い歩行経路があるならtrue */
    static boolean path(@NotNull Location from, @NotNull Location to) {
        if (from.getWorld() != to.getWorld() || squared(from, to) > 20.0D * 20.0D) return false;
        Vector delta = to.toVector().subtract(from.toVector()).setY(0);
        int steps = Math.max(1, (int) Math.ceil(delta.length() / 0.5D));
        Vector side = delta.lengthSquared() > 0.01D
            ? new Vector(-delta.getZ(), 0, delta.getX()).normalize().multiply(0.35D) : new Vector();
        double previous = from.getY();
        for (int i = 0; i <= steps; i++) {
            Location probe = from.clone().add(delta.clone().multiply(i / (double) steps));
            probe.setY(previous);
            Location surface = ground(probe);
            if (surface == null || Math.abs(surface.getY() - previous) > 1.0D
                || !walkable(surface.clone().add(side)) || !walkable(surface.clone().subtract(side))) return false;
            previous = surface.getY();
        }
        return sight(from, to);
    }

    /** @return 視線の途中に遮蔽ブロックがないならtrue */
    static boolean sight(@NotNull Location from, @NotNull Location to) {
        if (from.getWorld() == null || from.getWorld() != to.getWorld()) return false;
        Location origin = from.clone().add(0, 1.0D, 0), end = to.clone().add(0, 0.9D, 0);
        Vector direction = end.toVector().subtract(origin.toVector());
        if (direction.lengthSquared() < 0.01D) return true;
        double distance = direction.length();
        return from.getWorld().rayTraceBlocks(origin, direction.normalize(), Math.max(0, distance - 0.25D),
            FluidCollisionMode.NEVER, true) == null;
    }

    /** @return 読込済みchunkにあるworld高さ内の地点ならtrue */
    static boolean loaded(@NotNull Location point) {
        World world = point.getWorld();
        return world != null && point.getY() > world.getMinHeight() && point.getY() < world.getMaxHeight() - 2
            && world.isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4);
    }

    /** 全会場の外接chunkが読込済みの時だけ予兆を作成します。 */
    static boolean fullyLoaded(@NotNull Location center) {
        if (!loaded(center)) return false;
        World world = center.getWorld();
        for (int x = (int) Math.floor(center.getX() - RADIUS) >> 4;
             x <= (int) Math.floor(center.getX() + RADIUS) >> 4; x++) {
            for (int z = (int) Math.floor(center.getZ() - RADIUS) >> 4;
                 z <= (int) Math.floor(center.getZ() + RADIUS) >> 4; z++) {
                if (!world.isChunkLoaded(x, z)) return false;
            }
        }
        return true;
    }

    /** 危険域の外にある、7m以内に歩いて届く小さな安全輪を探します。 */
    static @Nullable Location safe(@NotNull Location from, @NotNull Location arena,
                                  @NotNull Predicate<Location> danger) {
        Location start = ground(from);
        if (start == null) return null;
        if (safeCircle(start, arena, danger, 0.65D)) return start;
        for (double radius : new double[] {2.0D, 3.5D, 5.0D, 7.0D}) {
            for (int i = 0; i < 24; i++) {
                double angle = i * Math.PI / 12.0D;
                Location candidate = ground(start.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
                if (candidate != null && safeCircle(candidate, arena, danger, 0.65D) && path(start, candidate)) return candidate;
            }
        }
        return null;
    }

    /** @return 円周の全体が会場内の安全な支持面にあるならtrue */
    static boolean safeCircle(@NotNull Location point, @NotNull Location arena,
                              @NotNull Predicate<Location> danger, double radius) {
        if (!contains(point, arena) || !walkable(point) || danger.test(point)) return false;
        for (int i = 0; i < 12; i++) {
            Location rim = point.clone().add(Math.cos(i * Math.PI / 6) * radius, 0, Math.sin(i * Math.PI / 6) * radius);
            Location surface = ground(rim);
            if (surface == null || !contains(surface, arena) || Math.abs(surface.getY() - point.getY()) > 1.0D
                || danger.test(surface)) return false;
        }
        return true;
    }

    /** 召喚する芽の足場を中心から歩ける範囲に、互いを離して探します。 */
    static @NotNull List<Location> budAnchors(@NotNull Location center, int count) {
        List<Location> result = new ArrayList<>(count);
        Location start = ground(center);
        if (start == null) return List.of();
        for (int slot = 0; slot < count; slot++) {
            Location chosen = null;
            for (double radius : new double[] {6.0D, 7.5D, 5.0D}) {
                for (int offset = 0; offset < 8; offset++) {
                    double angle = Math.PI * 2 * slot / count + offset * Math.PI / 16;
                    Location candidate = ground(center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
                    if (candidate != null && safeCircle(candidate, center, ignored -> false, 0.8D)
                        && result.stream().allMatch(other -> squared(candidate, other) >= 9.0D)
                        && path(start, candidate)) { chosen = candidate; break; }
                }
                if (chosen != null) break;
            }
            if (chosen == null) return List.of();
            result.add(chosen);
        }
        return List.copyOf(result);
    }

    /** 地表へ投影した円周を有限個の表示地点として返します。 */
    static @NotNull List<Location> ring(@NotNull Location center, double radius, int count) {
        List<Location> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2 * i / count;
            Location surface = ground(center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
            if (surface != null) points.add(surface.add(0, 0.15D, 0));
        }
        return points;
    }
}
