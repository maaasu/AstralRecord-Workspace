package io.github.maaasu.astralRecord.feature.boss.service;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** ボス攻撃の命中面を、発動時の粒子用に有限個の地点へ変換します。 */
final class BossDamageAreaEffect {
    private static final int MAX_POINTS = 96;

    private BossDamageAreaEffect() {}

    /**
     * 水平な攻撃面を中心基準の格子で走査し、命中条件を満たす地点だけを均等に抽出します。
     *
     * @param center 攻撃面の中心
     * @param extent 中心から走査する水平距離
     * @param spacing 格子の間隔
     * @param contains 実際の攻撃面に入るかを判定する関数
     * @return 近傍の床面へ投影した最大96地点
     */
    static @NotNull List<Location> sampleArea(
        @NotNull Location center,
        double extent,
        double spacing,
        @NotNull Predicate<Location> contains
    ) {
        return sampleArea(center, extent, spacing, contains, point -> true);
    }

    /**
     * 水平形状を先に絞り、床投影後に遮蔽などの追加条件を適用します。
     * @param center 攻撃面の中心
     * @param extent 水平走査距離
     * @param spacing 格子間隔
     * @param contains 水平形状の条件。中心の高さと投影後の高さで判定する
     * @param surfaceFilter 床面でだけ評価する追加条件
     * @return 攻撃形状内かつ追加条件を満たす最大96地点
     */
    static @NotNull List<Location> sampleArea(
        @NotNull Location center,
        double extent,
        double spacing,
        @NotNull Predicate<Location> contains,
        @NotNull Predicate<Location> surfaceFilter
    ) {
        World world = center.getWorld();
        if (world == null || extent <= 0.0D || spacing <= 0.0D) {
            return List.of();
        }
        int steps = (int) Math.ceil(extent / spacing);
        double step = extent / steps;
        List<Location> candidates = new ArrayList<>();
        for (int x = -steps; x <= steps; x++) {
            for (int z = -steps; z <= steps; z++) {
                Location point = center.clone().add(x * step, 0.0D, z * step);
                if (!contains.test(point)) continue;
                Location surface = surfaceNear(world, point);
                if (surface != null && contains.test(surface) && surfaceFilter.test(surface)) {
                    candidates.add(surface.add(0.0D, 0.6D, 0.0D));
                }
            }
        }
        return limit(candidates);
    }

    /** 読み込み済みchunkの攻撃高さ近傍で、粒子が埋まらない床の上面を探します。 */
    private static Location surfaceNear(@NotNull World world, @NotNull Location point) {
        int x = point.getBlockX();
        int z = point.getBlockZ();
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        int baseY = point.getBlockY();
        Location nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (int y = Math.min(baseY + 2, world.getMaxHeight() - 1);
             y >= Math.max(baseY - 4, world.getMinHeight()); y--) {
            Block floor = world.getBlockAt(x, y, z);
            if (!floor.getType().isSolid() || floor.isPassable() || floor.isLiquid()
                || y + 1 >= world.getMaxHeight() || !world.getBlockAt(x, y + 1, z).isPassable()) {
                continue;
            }
            double top = floor.getBoundingBox().getMaxY();
            double distance = Math.abs(top - point.getY());
            if (distance <= 3.0D && distance < nearestDistance) {
                nearest = point.clone();
                nearest.setY(top);
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /**
     * 形状から得た候補地点を全域に分散する最大96地点へ縮めます。
     *
     * @param candidates 攻撃面内の候補地点
     * @return 間引いた変更不能な地点一覧
     */
    static @NotNull List<Location> limit(@NotNull List<Location> candidates) {
        if (candidates.size() <= MAX_POINTS) {
            return List.copyOf(candidates);
        }
        List<Location> result = new ArrayList<>(MAX_POINTS);
        for (int index = 0; index < MAX_POINTS; index++) {
            result.add(candidates.get(index * candidates.size() / MAX_POINTS));
        }
        return List.copyOf(result);
    }
}
