package io.github.maaasu.astralRecord.feature.mob.skill.pond;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 森林池の浅水面と岸を同じ攻撃面へ投影します。未読込chunkは読み込みません。 */
public final class PondSurface {
    private PondSurface() { }

    /**
     * 基準の戦闘階の上下2mだけを調べ、水面または衝突する床の上を返します。
     * @param point 探すXZと基準Y
     * @return 頭上2mが開いた浅水面または岸。安全な面がない場合null
     */
    public static @Nullable Location at(@NotNull Location point) {
        World world = point.getWorld();
        if (world == null || !world.isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4)) return null;
        for (int y = Math.min(world.getMaxHeight() - 3, point.getBlockY() + 1);
             y >= Math.max(world.getMinHeight() + 1, point.getBlockY() - 2); y--) {
            Block floor = world.getBlockAt(point.getBlockX(), y, point.getBlockZ());
            boolean water = water(floor);
            if (!water && floor.isPassable()) continue;
            if (water && (floor.getRelative(0, -1, 0).isPassable()
                || water(floor.getRelative(0, -1, 0)))) continue;
            if (!floor.getRelative(0, 1, 0).isPassable() || !floor.getRelative(0, 2, 0).isPassable()
                || water(floor.getRelative(0, 1, 0)) || water(floor.getRelative(0, 2, 0))
                || floor.getRelative(0, 1, 0).getType() == Material.LAVA
                || floor.getType() == Material.LAVA || floor.getType() == Material.MAGMA_BLOCK) continue;
            double height = water ? y + 1.0D : floor.getBoundingBox().getMaxY();
            return new Location(world, point.getX(), height + 0.08D, point.getZ());
        }
        return null;
    }

    /** 水草やwaterloggedを含む水面判定。深い水の底を歩行面として採用しません。 */
    private static boolean water(Block block) {
        return block.getType() == Material.WATER || block.getType() == Material.SEAGRASS
            || block.getType() == Material.TALL_SEAGRASS
            || block.getBlockData() instanceof Waterlogged data && data.isWaterlogged();
    }

    /** @param a 始点 @param b 終点 @return 同じworldで頭上の射線を壁が遮らない場合true */
    public static boolean clear(@NotNull Location a, @NotNull Location b) {
        if (a.getWorld() == null || a.getWorld() != b.getWorld()) return false;
        Vector delta = b.toVector().subtract(a.toVector());
        double length = delta.length();
        if (length < 0.01D) return true;
        int steps = Math.max(1, (int) Math.ceil(length));
        for (int i = 0; i <= steps; i++) {
            Location p = a.clone().add(delta.clone().multiply((double) i / steps));
            if (!a.getWorld().isChunkLoaded(p.getBlockX() >> 4, p.getBlockZ() >> 4)) return false;
        }
        return a.getWorld().rayTraceBlocks(a.clone().add(0, 0.55D, 0), delta, length,
            FluidCollisionMode.NEVER, true) == null;
    }

    /** @param a 始点 @param b 終点 @return 両端と0.5m刻みの途中に歩行可能な浅水・岸がある場合true */
    public static boolean walkable(@NotNull Location a, @NotNull Location b) {
        if (!clear(a, b)) return false;
        Vector delta = b.toVector().subtract(a.toVector());
        int steps = Math.max(1, (int) Math.ceil(delta.length() / 0.5D));
        for (int i = 0; i <= steps; i++) if (at(a.clone().add(delta.clone().multiply((double) i / steps))) == null) return false;
        return true;
    }

    /** @param a 比較元 @param b 比較先 @return 水平距離の二乗 */
    public static double squared(@NotNull Location a, @NotNull Location b) {
        double x = a.getX() - b.getX(), z = a.getZ() - b.getZ();
        return x * x + z * z;
    }

    /** @param point 対象 @param from 線分始点 @param to 線分終点 @return 水平線分までの距離の二乗 */
    public static double segmentSquared(@NotNull Location point, @NotNull Location from, @NotNull Location to) {
        double x = to.getX() - from.getX(), z = to.getZ() - from.getZ();
        double length = x * x + z * z;
        double t = length < 0.0001D ? 0.0D : Math.clamp(
            ((point.getX() - from.getX()) * x + (point.getZ() - from.getZ()) * z) / length, 0.0D, 1.0D);
        double dx = point.getX() - from.getX() - t * x, dz = point.getZ() - from.getZ() - t * z;
        return dx * dx + dz * dz;
    }
}
