package io.github.maaasu.astralRecord.feature.dungeon.generation;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Bukkit API に依存せず、BSP で部屋と接続木を決定する純粋な生成器です。
 * 同じマスタと seed からは常に同じ配置を返します。
 */
public final class DungeonLayoutPlanner {
    private static final int PARTITION_MARGIN = 2;
    private static final int MAX_LAYOUT_ATTEMPTS = 64;
    private static final long ATTEMPT_SALT = 0x9E3779B97F4A7C15L;

    /**
     * BSP 配置を生成します。
     *
     * @param definition ダンジョン定義
     * @param seed 再現用 seed
     * @return 配置計画
     */
    public @NotNull DungeonLayout plan(@NotNull DungeonDefinition definition, long seed) {
        DungeonDefinition.Generation generation = definition.generation();
        int targetRoomCount = chooseInclusive(
                new SplittableRandom(seed ^ 0x6A09E667F3BCC909L),
                generation.roomCount()
        );

        for (int attempt = 0; attempt < MAX_LAYOUT_ATTEMPTS; attempt++) {
            SplittableRandom random = new SplittableRandom(seed + ATTEMPT_SALT * attempt);
            DungeonLayout layout = tryPlan(generation, seed, targetRoomCount, random);
            if (layout != null) {
                return layout;
            }
        }
        throw new IllegalArgumentException(
                "BSP could not place " + targetRoomCount + " rooms in "
                        + generation.areaWidth() + "x" + generation.areaDepth()
        );
    }

    private DungeonLayout tryPlan(
            @NotNull DungeonDefinition.Generation generation,
            long seed,
            int targetRoomCount,
            @NotNull SplittableRandom random
    ) {
        Node root = new Node(new DungeonLayout.Rect(
                0,
                0,
                generation.areaWidth() - 1,
                generation.areaDepth() - 1
        ));
        List<Node> leaves = new ArrayList<>();
        leaves.add(root);
        int minimumPartitionSize = generation.roomSize().min() + PARTITION_MARGIN * 2;

        while (leaves.size() < targetRoomCount) {
            List<Node> splittable = leaves.stream()
                    .filter(node -> canSplit(node.bounds, minimumPartitionSize))
                    .sorted(Comparator
                            .comparingInt((Node node) -> node.bounds.width() * node.bounds.depth())
                            .reversed()
                            .thenComparingInt(node -> node.bounds.minX())
                            .thenComparingInt(node -> node.bounds.minZ()))
                    .toList();
            if (splittable.isEmpty()) {
                return null;
            }

            int candidateWindow = Math.min(3, splittable.size());
            Node selected = splittable.get(random.nextInt(candidateWindow));
            if (!split(selected, generation, minimumPartitionSize, random)) {
                return null;
            }
            leaves.remove(selected);
            leaves.add(selected.left);
            leaves.add(selected.right);
        }

        leaves.sort(Comparator
                .comparingInt((Node node) -> node.bounds.minX())
                .thenComparingInt(node -> node.bounds.minZ()));
        List<PlacedRoom> placedRooms = new ArrayList<>(leaves.size());
        for (int id = 0; id < leaves.size(); id++) {
            Node leaf = leaves.get(id);
            DungeonLayout.Rect roomBounds = createRoomBounds(leaf.bounds, generation.roomSize(), random);
            DungeonRoomShape shape = chooseShape(generation.roomShapes(), random);
            leaf.roomId = id;
            placedRooms.add(new PlacedRoom(id, roomBounds, shape));
        }

        List<Edge> edges = new ArrayList<>(leaves.size() - 1);
        collectConnections(root, placedRooms, edges);
        if (edges.size() != leaves.size() - 1) {
            return null;
        }

        int startRoomId = placedRooms.stream()
                .min(Comparator
                        .comparingInt((PlacedRoom room) -> room.bounds.minX())
                        .thenComparingInt(room -> room.bounds.minZ())
                        .thenComparingInt(PlacedRoom::id))
                .orElseThrow()
                .id;

        Map<Integer, Set<Integer>> adjacency = adjacency(placedRooms.size(), edges);
        Traversal traversal = traverse(startRoomId, adjacency);
        int bossRoomId = placedRooms.stream()
                .filter(room -> room.id != startRoomId)
                .filter(room -> adjacency.get(room.id).size() == 1)
                .max(Comparator
                        .comparingInt((PlacedRoom room) -> traversal.distance.get(room.id))
                        .thenComparingInt(room -> room.bounds.centerX())
                        .thenComparingInt(room -> room.bounds.centerZ())
                        .thenComparingInt(PlacedRoom::id))
                .orElseThrow()
                .id;

        List<DungeonLayout.Room> rooms = new ArrayList<>(placedRooms.size());
        for (PlacedRoom room : placedRooms) {
            DungeonLayout.RoomRole role = room.id == startRoomId
                    ? DungeonLayout.RoomRole.START
                    : room.id == bossRoomId
                    ? DungeonLayout.RoomRole.BOSS
                    : DungeonLayout.RoomRole.NORMAL;
            rooms.add(new DungeonLayout.Room(
                    room.id,
                    room.bounds,
                    room.shape,
                    role,
                    traversal.distance.get(room.id)
            ));
        }

        Map<Integer, PlacedRoom> roomById = new HashMap<>();
        for (PlacedRoom room : placedRooms) {
            roomById.put(room.id, room);
        }
        List<Integer> childIds = traversal.parent.keySet().stream().sorted().toList();
        List<DungeonLayout.Connection> connections = new ArrayList<>(childIds.size());
        Set<DungeonLayout.Point> reservedCorridors = new LinkedHashSet<>();
        int connectionId = 0;
        for (int childId : childIds) {
            int parentId = traversal.parent.get(childId);
            PlacedRoom from = roomById.get(parentId);
            PlacedRoom to = roomById.get(childId);
            List<DungeonLayout.Point> centerLine = routeCenterLine(
                    from,
                    to,
                    placedRooms,
                    reservedCorridors,
                    generation,
                    random.nextBoolean()
            );
            if (centerLine == null) {
                return null;
            }
            reservedCorridors.addAll(centerLine);
            connections.add(new DungeonLayout.Connection(
                    connectionId++,
                    parentId,
                    childId,
                    centerLine
            ));
        }

        return new DungeonLayout(
                seed,
                generation.areaWidth(),
                generation.areaDepth(),
                generation.baseY(),
                generation.roomHeight(),
                rooms,
                connections,
                startRoomId,
                bossRoomId
        );
    }

    private boolean canSplit(@NotNull DungeonLayout.Rect bounds, int minimumPartitionSize) {
        return bounds.width() >= minimumPartitionSize * 2
                || bounds.depth() >= minimumPartitionSize * 2;
    }

    private boolean split(
            @NotNull Node node,
            @NotNull DungeonDefinition.Generation generation,
            int minimumPartitionSize,
            @NotNull SplittableRandom random
    ) {
        int width = node.bounds.width();
        int depth = node.bounds.depth();
        boolean canSplitX = width >= minimumPartitionSize * 2;
        boolean canSplitZ = depth >= minimumPartitionSize * 2;
        if (!canSplitX && !canSplitZ) {
            return false;
        }

        boolean splitX;
        if (!canSplitZ) {
            splitX = true;
        } else if (!canSplitX) {
            splitX = false;
        } else if (width > depth * 1.25D) {
            splitX = true;
        } else if (depth > width * 1.25D) {
            splitX = false;
        } else {
            splitX = random.nextBoolean();
        }

        int length = splitX ? width : depth;
        int ratioMin = (int) Math.ceil(length * generation.splitRatioMin());
        int ratioMax = (int) Math.floor(length * generation.splitRatioMax());
        int lower = Math.max(minimumPartitionSize, ratioMin);
        int upper = Math.min(length - minimumPartitionSize, ratioMax);
        if (lower > upper) {
            lower = minimumPartitionSize;
            upper = length - minimumPartitionSize;
        }
        if (lower > upper) {
            return false;
        }
        int firstLength = lower + random.nextInt(upper - lower + 1);

        if (splitX) {
            int leftMaxX = node.bounds.minX() + firstLength - 1;
            node.left = new Node(new DungeonLayout.Rect(
                    node.bounds.minX(), node.bounds.minZ(), leftMaxX, node.bounds.maxZ()));
            node.right = new Node(new DungeonLayout.Rect(
                    leftMaxX + 1, node.bounds.minZ(), node.bounds.maxX(), node.bounds.maxZ()));
        } else {
            int leftMaxZ = node.bounds.minZ() + firstLength - 1;
            node.left = new Node(new DungeonLayout.Rect(
                    node.bounds.minX(), node.bounds.minZ(), node.bounds.maxX(), leftMaxZ));
            node.right = new Node(new DungeonLayout.Rect(
                    node.bounds.minX(), leftMaxZ + 1, node.bounds.maxX(), node.bounds.maxZ()));
        }
        return true;
    }

    private @NotNull DungeonLayout.Rect createRoomBounds(
            @NotNull DungeonLayout.Rect partition,
            @NotNull DungeonDefinition.IntRange roomSize,
            @NotNull SplittableRandom random
    ) {
        int availableWidth = partition.width() - PARTITION_MARGIN * 2;
        int availableDepth = partition.depth() - PARTITION_MARGIN * 2;
        int maxWidth = Math.min(roomSize.max(), availableWidth);
        int maxDepth = Math.min(roomSize.max(), availableDepth);
        if (maxWidth < roomSize.min() || maxDepth < roomSize.min()) {
            throw new IllegalArgumentException("Partition is too small for a room");
        }
        int width = roomSize.min() + random.nextInt(maxWidth - roomSize.min() + 1);
        int depth = roomSize.min() + random.nextInt(maxDepth - roomSize.min() + 1);
        int minX = partition.minX() + PARTITION_MARGIN
                + random.nextInt(availableWidth - width + 1);
        int minZ = partition.minZ() + PARTITION_MARGIN
                + random.nextInt(availableDepth - depth + 1);
        return new DungeonLayout.Rect(minX, minZ, minX + width - 1, minZ + depth - 1);
    }

    private @NotNull DungeonRoomShape chooseShape(
            @NotNull List<DungeonDefinition.WeightedShape> shapes,
            @NotNull SplittableRandom random
    ) {
        int total = shapes.stream().mapToInt(DungeonDefinition.WeightedShape::weight).sum();
        int roll = random.nextInt(total);
        for (DungeonDefinition.WeightedShape shape : shapes) {
            roll -= shape.weight();
            if (roll < 0) {
                return shape.shape();
            }
        }
        return shapes.getLast().shape();
    }

    private @NotNull List<Integer> collectConnections(
            @NotNull Node node,
            @NotNull List<PlacedRoom> rooms,
            @NotNull List<Edge> edges
    ) {
        if (node.left == null || node.right == null) {
            return List.of(node.roomId);
        }
        List<Integer> leftRooms = collectConnections(node.left, rooms, edges);
        List<Integer> rightRooms = collectConnections(node.right, rooms, edges);
        Edge closest = null;
        int closestDistance = Integer.MAX_VALUE;
        for (int left : leftRooms) {
            for (int right : rightRooms) {
                PlacedRoom first = rooms.get(left);
                PlacedRoom second = rooms.get(right);
                int distance = Math.abs(first.bounds.centerX() - second.bounds.centerX())
                        + Math.abs(first.bounds.centerZ() - second.bounds.centerZ());
                if (distance < closestDistance
                        || (distance == closestDistance && compareEdge(left, right, closest) < 0)) {
                    closestDistance = distance;
                    closest = new Edge(left, right);
                }
            }
        }
        edges.add(closest);
        List<Integer> combined = new ArrayList<>(leftRooms.size() + rightRooms.size());
        combined.addAll(leftRooms);
        combined.addAll(rightRooms);
        return combined;
    }

    private int compareEdge(int first, int second, Edge other) {
        if (other == null) {
            return -1;
        }
        int firstCompare = Integer.compare(first, other.first);
        return firstCompare == 0 ? Integer.compare(second, other.second) : firstCompare;
    }

    private @NotNull Map<Integer, Set<Integer>> adjacency(int roomCount, @NotNull List<Edge> edges) {
        Map<Integer, Set<Integer>> result = new LinkedHashMap<>();
        for (int roomId = 0; roomId < roomCount; roomId++) {
            result.put(roomId, new LinkedHashSet<>());
        }
        for (Edge edge : edges) {
            result.get(edge.first).add(edge.second);
            result.get(edge.second).add(edge.first);
        }
        return result;
    }

    private @NotNull Traversal traverse(
            int startRoomId,
            @NotNull Map<Integer, Set<Integer>> adjacency
    ) {
        Map<Integer, Integer> parent = new LinkedHashMap<>();
        Map<Integer, Integer> distance = new LinkedHashMap<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        distance.put(startRoomId, 0);
        queue.add(startRoomId);
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            for (int next : adjacency.get(current).stream().sorted().toList()) {
                if (distance.containsKey(next)) {
                    continue;
                }
                parent.put(next, current);
                distance.put(next, distance.get(current) + 1);
                queue.addLast(next);
            }
        }
        if (distance.size() != adjacency.size()) {
            throw new IllegalStateException("Generated BSP graph is disconnected");
        }
        return new Traversal(parent, distance);
    }

    private List<DungeonLayout.Point> routeCenterLine(
            @NotNull PlacedRoom from,
            @NotNull PlacedRoom to,
            @NotNull List<PlacedRoom> rooms,
            @NotNull Set<DungeonLayout.Point> reservedCorridors,
            @NotNull DungeonDefinition.Generation generation,
            boolean xFirst
    ) {
        int roomClearance = generation.corridorWidth() / 2 + 1;
        int corridorClearance = generation.corridorWidth() + 1;
        Set<DungeonLayout.Point> blocked = new HashSet<>();
        for (PlacedRoom room : rooms) {
            if (room.id == from.id || room.id == to.id) {
                continue;
            }
            addInflated(blocked, room.bounds, roomClearance);
        }
        for (DungeonLayout.Point point : reservedCorridors) {
            addInflated(blocked, point, corridorClearance);
        }

        DungeonLayout.Point start = new DungeonLayout.Point(from.bounds.centerX(), from.bounds.centerZ());
        DungeonLayout.Point target = new DungeonLayout.Point(to.bounds.centerX(), to.bounds.centerZ());
        int margin = Math.max(8, corridorClearance + 3);
        int minX = -margin;
        int minZ = -margin;
        int maxX = generation.areaWidth() - 1 + margin;
        int maxZ = generation.areaDepth() - 1 + margin;
        int[][] directions = xFirst
                ? new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}
                : new int[][]{{0, 1}, {0, -1}, {1, 0}, {-1, 0}};

        PriorityQueue<RouteCandidate> open = new PriorityQueue<>(Comparator
                .comparingInt(RouteCandidate::estimatedTotal)
                .thenComparingInt(RouteCandidate::cost)
                .thenComparingLong(RouteCandidate::sequence));
        Map<RouteState, Integer> bestCost = new HashMap<>();
        Map<RouteState, RouteState> parent = new HashMap<>();
        RouteState startState = new RouteState(start.x(), start.z(), -1);
        bestCost.put(startState, 0);
        long sequence = 0L;
        open.add(new RouteCandidate(startState, 0, manhattan(start, target) * 10, sequence++));
        RouteState reached = null;

        while (!open.isEmpty()) {
            RouteCandidate current = open.poll();
            if (current.cost != bestCost.getOrDefault(current.state, Integer.MAX_VALUE)) {
                continue;
            }
            if (current.state.x == target.x() && current.state.z == target.z()) {
                reached = current.state;
                break;
            }
            for (int directionIndex = 0; directionIndex < directions.length; directionIndex++) {
                int nextX = current.state.x + directions[directionIndex][0];
                int nextZ = current.state.z + directions[directionIndex][1];
                if (nextX < minX || nextX > maxX || nextZ < minZ || nextZ > maxZ) {
                    continue;
                }
                DungeonLayout.Point nextPoint = new DungeonLayout.Point(nextX, nextZ);
                boolean insideEndpoint = from.bounds.contains(nextX, nextZ) || to.bounds.contains(nextX, nextZ);
                if (!insideEndpoint && blocked.contains(nextPoint)) {
                    continue;
                }
                int turnCost = current.state.direction >= 0
                        && current.state.direction != directionIndex ? 3 : 0;
                int nextCost = current.cost + 10 + turnCost;
                RouteState nextState = new RouteState(nextX, nextZ, directionIndex);
                if (nextCost >= bestCost.getOrDefault(nextState, Integer.MAX_VALUE)) {
                    continue;
                }
                bestCost.put(nextState, nextCost);
                parent.put(nextState, current.state);
                int estimate = nextCost + manhattan(nextPoint, target) * 10;
                open.add(new RouteCandidate(nextState, nextCost, estimate, sequence++));
            }
        }
        if (reached == null) {
            return null;
        }

        ArrayDeque<DungeonLayout.Point> reversed = new ArrayDeque<>();
        RouteState current = reached;
        while (current != null) {
            reversed.addFirst(new DungeonLayout.Point(current.x, current.z));
            current = parent.get(current);
        }
        return List.copyOf(reversed);
    }

    private void addInflated(
            @NotNull Set<DungeonLayout.Point> blocked,
            @NotNull DungeonLayout.Rect bounds,
            int clearance
    ) {
        for (int x = bounds.minX() - clearance; x <= bounds.maxX() + clearance; x++) {
            for (int z = bounds.minZ() - clearance; z <= bounds.maxZ() + clearance; z++) {
                blocked.add(new DungeonLayout.Point(x, z));
            }
        }
    }

    private void addInflated(
            @NotNull Set<DungeonLayout.Point> blocked,
            @NotNull DungeonLayout.Point center,
            int clearance
    ) {
        for (int x = center.x() - clearance; x <= center.x() + clearance; x++) {
            for (int z = center.z() - clearance; z <= center.z() + clearance; z++) {
                blocked.add(new DungeonLayout.Point(x, z));
            }
        }
    }

    private int manhattan(@NotNull DungeonLayout.Point first, @NotNull DungeonLayout.Point second) {
        return Math.abs(first.x() - second.x()) + Math.abs(first.z() - second.z());
    }

    private int chooseInclusive(@NotNull SplittableRandom random, @NotNull DungeonDefinition.IntRange range) {
        return range.min() + random.nextInt(range.max() - range.min() + 1);
    }

    private static final class Node {
        private final DungeonLayout.Rect bounds;
        private Node left;
        private Node right;
        private int roomId = -1;

        private Node(@NotNull DungeonLayout.Rect bounds) {
            this.bounds = bounds;
        }
    }

    private record PlacedRoom(int id, @NotNull DungeonLayout.Rect bounds, @NotNull DungeonRoomShape shape) {
    }

    private record Edge(int first, int second) {
    }

    private record Traversal(
            @NotNull Map<Integer, Integer> parent,
            @NotNull Map<Integer, Integer> distance
    ) {
    }

    private record RouteState(int x, int z, int direction) {
    }

    private record RouteCandidate(
            @NotNull RouteState state,
            int cost,
            int estimatedTotal,
            long sequence
    ) {
    }
}
