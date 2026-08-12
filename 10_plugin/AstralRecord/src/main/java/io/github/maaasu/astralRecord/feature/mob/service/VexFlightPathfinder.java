package io.github.maaasu.astralRecord.feature.mob.service;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Vex 用の有界三次元 A* 経路探索を提供します。
 *
 * <p>探索空間と展開ノード数を呼び出し側が制限し、到達不能な場合は
 * 目標へ最も近づいた安全な部分経路を返します。</p>
 */
final class VexFlightPathfinder {

    private static final int[][] OFFSETS = createOffsets();

    private VexFlightPathfinder() {
    }

    /**
     * 指定した通過可否判定を使って三次元経路を探索します。
     *
     * @param start            探索開始ノード
     * @param goal             探索目標ノード
     * @param passable         ノード中心へ Vex が収まる場合に true を返す判定
     * @param horizontalRadius 開始位置からの水平探索半径
     * @param verticalRadius   開始位置からの上下探索半径
     * @param maxExpandedNodes 最大展開ノード数
     * @return 開始ノードを除く経路。到達不能時は安全な部分経路
     */
    static @NotNull List<GridPoint> findPath(
            @NotNull GridPoint start,
            @NotNull GridPoint goal,
            @NotNull Predicate<GridPoint> passable,
            int horizontalRadius,
            int verticalRadius,
            int maxExpandedNodes) {
        if (start.equals(goal)) {
            return List.of();
        }

        int safeHorizontalRadius = Math.max(1, horizontalRadius);
        int safeVerticalRadius = Math.max(1, verticalRadius);
        int safeNodeLimit = Math.max(1, maxExpandedNodes);
        PriorityQueue<OpenNode> open = new PriorityQueue<>(Comparator.comparingDouble(OpenNode::estimatedTotalCost));
        Map<GridPoint, Double> costs = new HashMap<>();
        Map<GridPoint, GridPoint> parents = new HashMap<>();
        Map<GridPoint, Boolean> passability = new HashMap<>();
        Set<GridPoint> closed = new HashSet<>();
        Predicate<GridPoint> cachedPassable = point -> passability.computeIfAbsent(point, passable::test);

        double initialHeuristic = heuristic(start, goal);
        open.add(new OpenNode(start, 0.0D, initialHeuristic));
        costs.put(start, 0.0D);
        GridPoint closest = start;
        double closestHeuristic = initialHeuristic;
        int expanded = 0;

        while (!open.isEmpty() && expanded < safeNodeLimit) {
            OpenNode currentOpen = open.poll();
            GridPoint current = currentOpen.point();
            if (!closed.add(current)) {
                continue;
            }
            expanded++;

            double currentHeuristic = heuristic(current, goal);
            if (currentHeuristic < closestHeuristic) {
                closest = current;
                closestHeuristic = currentHeuristic;
            }
            if (current.equals(goal)) {
                closest = current;
                break;
            }

            for (int[] offset : OFFSETS) {
                GridPoint next = current.add(offset[0], offset[1], offset[2]);
                if (!withinBounds(start, next, safeHorizontalRadius, safeVerticalRadius)
                        || closed.contains(next)
                        || !cachedPassable.test(next)
                        || cutsCorner(current, offset, cachedPassable)) {
                    continue;
                }

                double nextCost = currentOpen.pathCost() + stepCost(offset);
                if (nextCost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    continue;
                }
                costs.put(next, nextCost);
                parents.put(next, current);
                open.add(new OpenNode(next, nextCost, nextCost + heuristic(next, goal)));
            }
        }

        return reconstruct(start, closest, parents);
    }

    private static boolean withinBounds(
            @NotNull GridPoint start,
            @NotNull GridPoint point,
            int horizontalRadius,
            int verticalRadius) {
        return Math.abs(point.x() - start.x()) <= horizontalRadius
                && Math.abs(point.z() - start.z()) <= horizontalRadius
                && Math.abs(point.y() - start.y()) <= verticalRadius;
    }

    private static boolean cutsCorner(
            @NotNull GridPoint current,
            int @NotNull [] offset,
            @NotNull Predicate<GridPoint> passable) {
        int changedAxes = (offset[0] == 0 ? 0 : 1)
                + (offset[1] == 0 ? 0 : 1)
                + (offset[2] == 0 ? 0 : 1);
        if (changedAxes <= 1) {
            return false;
        }

        for (int mask = 1; mask < 7; mask++) {
            int dx = (mask & 1) == 0 ? 0 : offset[0];
            int dy = (mask & 2) == 0 ? 0 : offset[1];
            int dz = (mask & 4) == 0 ? 0 : offset[2];
            int axes = (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
            if (axes == 0 || axes == changedAxes) {
                continue;
            }
            if (!passable.test(current.add(dx, dy, dz))) {
                return true;
            }
        }
        return false;
    }

    private static double stepCost(int @NotNull [] offset) {
        return Math.sqrt(offset[0] * offset[0] + offset[1] * offset[1] + offset[2] * offset[2]);
    }

    private static double heuristic(@NotNull GridPoint from, @NotNull GridPoint to) {
        long dx = (long) to.x() - from.x();
        long dy = (long) to.y() - from.y();
        long dz = (long) to.z() - from.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static @NotNull List<GridPoint> reconstruct(
            @NotNull GridPoint start,
            @NotNull GridPoint end,
            @NotNull Map<GridPoint, GridPoint> parents) {
        if (start.equals(end)) {
            return List.of();
        }
        List<GridPoint> reversed = new ArrayList<>();
        GridPoint current = end;
        while (!current.equals(start)) {
            reversed.add(current);
            current = parents.get(current);
            if (current == null) {
                return List.of();
            }
        }
        List<GridPoint> path = new ArrayList<>(reversed.size());
        for (int index = reversed.size() - 1; index >= 0; index--) {
            path.add(reversed.get(index));
        }
        return List.copyOf(path);
    }

    private static int @NotNull [][] createOffsets() {
        List<int[]> offsets = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        offsets.add(new int[]{dx, dy, dz});
                    }
                }
            }
        }
        return offsets.toArray(int[][]::new);
    }

    /** 三次元探索格子の整数座標です。 */
    record GridPoint(int x, int y, int z) {
        private @NotNull GridPoint add(int dx, int dy, int dz) {
            return new GridPoint(x + dx, y + dy, z + dz);
        }
    }

    private record OpenNode(GridPoint point, double pathCost, double estimatedTotalCost) {
    }
}
