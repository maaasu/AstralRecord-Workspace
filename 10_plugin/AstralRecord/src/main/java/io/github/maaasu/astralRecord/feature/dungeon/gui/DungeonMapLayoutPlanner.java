package io.github.maaasu.astralRecord.feature.dungeon.gui;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 部屋中心座標を inventory GUI の決定的な5x9配置へ射影します。 */
public final class DungeonMapLayoutPlanner {
    public static final int COLUMNS = 9;
    public static final int ROWS = 5;
    public static final int PAGE_SIZE = COLUMNS * ROWS;

    /**
     * 部屋を相対位置に沿ってページ・slotへ配置します。
     * 衝突時はマンハッタン距離、slot順で最寄り空きへ送り、45室超は次ページへ送ります。
     */
    public @NotNull List<Placement> plan(@NotNull DungeonLayout layout) {
        if (layout.rooms().isEmpty()) {
            return List.of();
        }
        int minX = layout.rooms().stream().mapToInt(room -> room.bounds().centerX()).min().orElse(0);
        int maxX = layout.rooms().stream().mapToInt(room -> room.bounds().centerX()).max().orElse(minX);
        int minZ = layout.rooms().stream().mapToInt(room -> room.bounds().centerZ()).min().orElse(0);
        int maxZ = layout.rooms().stream().mapToInt(room -> room.bounds().centerZ()).max().orElse(minZ);
        List<Placement> result = new ArrayList<>();
        List<Set<Integer>> occupiedByPage = new ArrayList<>();
        for (DungeonLayout.Room room : layout.rooms().stream()
                .sorted(Comparator.comparingInt(DungeonLayout.Room::id)).toList()) {
            int column = project(room.bounds().centerX(), minX, maxX, COLUMNS - 1);
            int row = project(room.bounds().centerZ(), minZ, maxZ, ROWS - 1);
            int page = 0;
            int slot;
            while (true) {
                if (page == occupiedByPage.size()) {
                    occupiedByPage.add(new HashSet<>());
                }
                slot = nearestFree(column, row, occupiedByPage.get(page));
                if (slot >= 0) {
                    occupiedByPage.get(page).add(slot);
                    break;
                }
                page++;
            }
            result.add(new Placement(room.id(), page, slot));
        }
        return List.copyOf(result);
    }

    /**
     * 部屋の配置結果から、同一ページ内の部屋同士を結ぶ通路 slot を決定します。
     * 部屋 slot を避けた最短経路を使い、同じ slot へ重なる通路は最初の接続だけを表示します。
     * ページをまたぐ接続は各ページ内で表現できないため、省略します。
     *
     * @param layout ダンジョン配置
     * @param placements {@link #plan(DungeonLayout)} で得た部屋配置
     * @return GUI 上へ表示する通路配置
     */
    public @NotNull List<CorridorPlacement> planCorridors(
            @NotNull DungeonLayout layout,
            @NotNull List<Placement> placements
    ) {
        if (layout.connections().isEmpty() || placements.isEmpty()) {
            return List.of();
        }
        Map<Integer, Placement> placementByRoom = new HashMap<>();
        Map<PageSlot, Integer> roomBySlot = new HashMap<>();
        placements.forEach(placement -> {
            placementByRoom.put(placement.roomId(), placement);
            roomBySlot.put(new PageSlot(placement.page(), placement.slot()), placement.roomId());
        });

        Map<PageSlot, CorridorPlacement> corridorBySlot = new LinkedHashMap<>();
        for (DungeonLayout.Connection connection : layout.connections().stream()
                .sorted(Comparator.comparingInt(DungeonLayout.Connection::id)).toList()) {
            Placement from = placementByRoom.get(connection.fromRoomId());
            Placement to = placementByRoom.get(connection.toRoomId());
            if (from == null || to == null || from.page() != to.page()
                    || from.slot() == to.slot()) {
                continue;
            }
            PageSlot start = new PageSlot(from.page(), from.slot());
            PageSlot end = new PageSlot(to.page(), to.slot());
            Set<Integer> blockedRooms = roomBySlot.keySet().stream()
                    .filter(slot -> slot.page() == from.page())
                    .map(PageSlot::slot)
                    .collect(java.util.stream.Collectors.toSet());
            blockedRooms.remove(start.slot());
            blockedRooms.remove(end.slot());
            List<Integer> path = shortestPath(from.slot(), to.slot(), blockedRooms);
            for (int index = 1; index < path.size() - 1; index++) {
                PageSlot slot = new PageSlot(from.page(), path.get(index));
                if (!roomBySlot.containsKey(slot)) {
                    corridorBySlot.putIfAbsent(slot,
                            new CorridorPlacement(connection.id(), slot.page(), slot.slot()));
                }
            }
        }
        return corridorBySlot.values().stream()
                .sorted(Comparator.comparingInt(CorridorPlacement::page)
                        .thenComparingInt(CorridorPlacement::slot))
                .toList();
    }

    private @NotNull List<Integer> shortestPath(
            int startSlot,
            int endSlot,
            @NotNull Set<Integer> blockedRooms
    ) {
        int[] previous = new int[PAGE_SIZE];
        java.util.Arrays.fill(previous, -1);
        boolean[] visited = new boolean[PAGE_SIZE];
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(startSlot);
        visited[startSlot] = true;
        int[] deltaColumns = {1, 0, -1, 0};
        int[] deltaRows = {0, 1, 0, -1};
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            if (current == endSlot) {
                break;
            }
            int column = current % COLUMNS;
            int row = current / COLUMNS;
            for (int index = 0; index < deltaColumns.length; index++) {
                int nextColumn = column + deltaColumns[index];
                int nextRow = row + deltaRows[index];
                if (nextColumn < 0 || nextColumn >= COLUMNS || nextRow < 0 || nextRow >= ROWS) {
                    continue;
                }
                int next = nextRow * COLUMNS + nextColumn;
                if (visited[next] || (blockedRooms.contains(next)
                        && next != endSlot && next != startSlot)) {
                    continue;
                }
                visited[next] = true;
                previous[next] = current;
                queue.addLast(next);
            }
        }
        if (!visited[endSlot]) {
            return directPath(startSlot, endSlot);
        }
        List<Integer> reversed = new ArrayList<>();
        for (int current = endSlot; current >= 0; current = previous[current]) {
            reversed.add(current);
            if (current == startSlot) {
                break;
            }
        }
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private @NotNull List<Integer> directPath(int startSlot, int endSlot) {
        int startColumn = startSlot % COLUMNS;
        int startRow = startSlot / COLUMNS;
        int endColumn = endSlot % COLUMNS;
        int endRow = endSlot / COLUMNS;
        List<Integer> path = new ArrayList<>();
        int column = startColumn;
        int row = startRow;
        path.add(row * COLUMNS + column);
        while (column != endColumn) {
            column += Integer.compare(endColumn, column);
            path.add(row * COLUMNS + column);
        }
        while (row != endRow) {
            row += Integer.compare(endRow, row);
            path.add(row * COLUMNS + column);
        }
        return path;
    }

    private int project(int value, int minimum, int maximum, int targetMaximum) {
        return maximum == minimum ? targetMaximum / 2
                : (int) Math.round((double) (value - minimum) * targetMaximum / (maximum - minimum));
    }

    private int nearestFree(int targetColumn, int targetRow, @NotNull Set<Integer> occupied) {
        return java.util.stream.IntStream.range(0, PAGE_SIZE)
                .filter(slot -> !occupied.contains(slot))
                .boxed()
                .min(Comparator
                        .comparingInt((Integer slot) ->
                                Math.abs(slot % COLUMNS - targetColumn)
                                        + Math.abs(slot / COLUMNS - targetRow))
                        .thenComparingInt(Integer::intValue))
                .orElse(-1);
    }

    public record Placement(int roomId, int page, int slot) {
    }

    /** GUI 上の通路表示位置です。 */
    public record CorridorPlacement(int connectionId, int page, int slot) {
    }

    private record PageSlot(int page, int slot) {
    }
}
