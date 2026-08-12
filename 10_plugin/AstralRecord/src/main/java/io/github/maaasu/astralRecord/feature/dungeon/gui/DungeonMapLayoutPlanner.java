package io.github.maaasu.astralRecord.feature.dungeon.gui;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
}
