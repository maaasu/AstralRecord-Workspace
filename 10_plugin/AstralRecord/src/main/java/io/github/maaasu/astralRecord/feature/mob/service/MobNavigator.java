package io.github.maaasu.astralRecord.feature.mob.service;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * A* アルゴリズムを用いてブロック衝突判定を考慮した経路を探索するユーティリティ。
 *
 * <p>ステップアップ最大1ブロック、落下最大3ブロックの移動を許容し、
 * バニラモブに近い経路計算を実現します。全メソッドはメインスレッドから呼び出すことを前提とします。</p>
 */
public final class MobNavigator {

    /** 最大探索ノード数。超過時は探索を打ち切り空リストを返す。 */
    private static final int MAX_SEARCH_NODES = 800;

    /** 生成するウェイポイントの最大数。 */
    private static final int MAX_PATH_WAYPOINTS = 32;

    private static final double BODY_RADIUS = 0.31;
    private static final double BODY_HEIGHT = 1.8;
    private static final double COLLISION_EPSILON = 1.0E-4;
    private static final double LOW_DECORATION_HEIGHT = 0.45;

    /**
     * 水平方向の隣接マス定義（東西南北および斜め4方向）。
     * 各要素は {dx, dz}。
     */
    private static final int[][] NEIGHBORS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1},
        {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private MobNavigator() {}

    /**
     * 開始位置から目標位置への経路を A* で探索して返します。
     *
     * <p>経路が見つからない場合、またはノード数制限に達した場合は空のリストを返します。
     * 戻り値のリストはスタート位置を含まず、各要素はブロック中央座標です。</p>
     *
     * @param from  開始位置（モブの現在位置）
     * @param to    目標位置
     * @return ウェイポイントのリスト（変更可能）。見つからない場合は空リスト
     */
    @NotNull
    public static List<Location> findPath(@NotNull Location from, @NotNull Location to) {
        World world = from.getWorld();
        if (world == null || world != to.getWorld()) return new ArrayList<>();

        int sx = (int) Math.floor(from.getX());
        int sz = (int) Math.floor(from.getZ());
        int sy = findStandableY(world, sx, (int) Math.floor(from.getY()), sz);
        int ex = (int) Math.floor(to.getX());
        int ez = (int) Math.floor(to.getZ());
        int ey = findStandableY(world, ex, (int) Math.floor(to.getY()), ez);
        if (sy < 0 || ey < 0) return new ArrayList<>();
        if (sx == ex && sy == ey && sz == ez) return new ArrayList<>();

        Node startNode = new Node(sx, sy, sz, null, 0.0, heuristic(sx, sy, sz, ex, ey, ez));
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<NodeKey, Double> gMap = new HashMap<>();
        Set<NodeKey> closed = new HashSet<>();

        open.add(startNode);
        gMap.put(new NodeKey(sx, sy, sz), 0.0);

        int iterations = 0;
        while (!open.isEmpty() && iterations++ < MAX_SEARCH_NODES) {
            Node cur = open.poll();
            NodeKey curKey = new NodeKey(cur.x, cur.y, cur.z);
            if (closed.contains(curKey)) continue;
            closed.add(curKey);

            if (cur.x == ex && cur.y == ey && cur.z == ez) {
                return buildPath(world, cur);
            }

            for (int[] dir : NEIGHBORS) {
                int nx = cur.x + dir[0];
                int nz = cur.z + dir[1];
                boolean diagonal = dir[0] != 0 && dir[1] != 0;

                // 斜め移動は両隣が通れる場合のみ許可
                if (diagonal) {
                    if (findStandableY(world, cur.x + dir[0], cur.y, cur.z) < 0) continue;
                    if (findStandableY(world, cur.x, cur.y, cur.z + dir[1]) < 0) continue;
                }

                int ny = findStandableY(world, nx, cur.y, nz);
                if (ny < 0) continue;
                if (ny > cur.y + 1) continue;               // ステップアップは1ブロックまで
                double stepCost = (diagonal ? 1.414 : 1.0) + Math.abs(ny - cur.y) * 0.5;
                double ng = cur.g + stepCost;
                NodeKey nKey = new NodeKey(nx, ny, nz);
                if (!closed.contains(nKey) && ng < gMap.getOrDefault(nKey, Double.MAX_VALUE)) {
                    gMap.put(nKey, ng);
                    double h = heuristic(nx, ny, nz, ex, ey, ez);
                    open.add(new Node(nx, ny, nz, cur, ng, ng + h));
                }
            }
        }

        return new ArrayList<>();
    }

    /**
     * 指定 X, Z 座標でモブが立てる足元 Y 座標を返します。
     *
     * <p>まず {@code startY} を確認し、次に1ブロック上（ステップアップ）、
     * 次にワールド下限まで下方向を探索します。</p>
     *
     * @param world  ワールド
     * @param x      X 座標
     * @param startY 探索開始 Y（足元）
     * @param z      Z 座標
     * @return 立てる足元 Y 座標。見つからなければ -1
     */
    public static int findStandableY(@NotNull World world, int x, int startY, int z) {
        if (canStandAt(world, x, startY, z)) return startY;
        if (canStandAt(world, x, startY + 1, z)) return startY + 1;
        for (int y = startY - 1; y >= world.getMinHeight(); y--) {
            if (canStandAt(world, x, y, z)) return y;
        }
        return -1;
    }

    /**
     * 指定位置にモブが立てるか判定します。
     * 床（y-1）が非通過可、足元（y）と頭部（y+1）が通過可であることを条件とします。
     *
     * @param world ワールド
     * @param x     X 座標
     * @param y     足元 Y 座標
     * @param z     Z 座標
     * @return 立てる場合 {@code true}
     */
    public static boolean canStandAt(@NotNull World world, int x, int y, int z) {
        if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) return false;
        Block floor = world.getBlockAt(x, y - 1, z);
        return !floor.isPassable() && hasBodyClearance(world, x + 0.5, y, z + 0.5);
    }

    /**
     * Mob の表示上の幅を考慮して、指定座標に体を置けるか判定します。
     *
     * @param world   ワールド
     * @param centerX Mob 中心 X 座標
     * @param feetY   Mob 足元 Y 座標
     * @param centerZ Mob 中心 Z 座標
     * @return 体のクリアランスがある場合は {@code true}
     */
    public static boolean hasBodyClearance(@NotNull World world, double centerX, double feetY, double centerZ) {
        if (feetY <= world.getMinHeight() || feetY + BODY_HEIGHT >= world.getMaxHeight()) return false;

        BoundingBox bodyBox = new BoundingBox(
                centerX - BODY_RADIUS,
                feetY + COLLISION_EPSILON,
                centerZ - BODY_RADIUS,
                centerX + BODY_RADIUS,
                feetY + BODY_HEIGHT,
                centerZ + BODY_RADIUS
        );
        int minX = (int) Math.floor(bodyBox.getMinX());
        int maxX = (int) Math.floor(bodyBox.getMaxX());
        int minY = (int) Math.floor(bodyBox.getMinY());
        int maxY = (int) Math.floor(bodyBox.getMaxY());
        int minZ = (int) Math.floor(bodyBox.getMinZ());
        int maxZ = (int) Math.floor(bodyBox.getMaxZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (isLiquid(block)) continue;
                    if (!block.getCollisionShape().overlaps(bodyBox)) continue;
                    if (isLowDecoration(block, feetY)) continue;
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isLiquid(@NotNull Block block) {
        Material type = block.getType();
        return type == Material.WATER || type == Material.LAVA;
    }

    private static boolean isLowDecoration(@NotNull Block block, double feetY) {
        BoundingBox blockBox = block.getBoundingBox();
        return blockBox.getVolume() > 0.0 && blockBox.getMaxY() <= feetY + LOW_DECORATION_HEIGHT;
    }

    private static double heuristic(int x, int y, int z, int ex, int ey, int ez) {
        double dx = x - ex;
        double dz = z - ez;
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(y - ey) * 0.5;
    }

    @NotNull
    private static List<Location> buildPath(@NotNull World world, @NotNull Node goal) {
        Deque<Node> stack = new ArrayDeque<>();
        Node n = goal;
        while (n != null) {
            stack.push(n);
            n = n.parent;
        }
        stack.poll(); // スタートノードを除去

        List<Location> path = new ArrayList<>();
        for (Node node : stack) {
            if (path.size() >= MAX_PATH_WAYPOINTS) break;
            path.add(new Location(world, node.x + 0.5, node.y, node.z + 0.5));
        }
        return path;
    }

    private record NodeKey(int x, int y, int z) {}

    private static final class Node {
        final int x, y, z;
        final Node parent;
        final double g;
        final double f;

        Node(int x, int y, int z, Node parent, double g, double f) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.parent = parent;
            this.g = g;
            this.f = f;
        }
    }
}
