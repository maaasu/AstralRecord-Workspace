package io.github.maaasu.astralRecord.feature.dungeon.model;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/** Bukkit ワールドへ順次反映できる、確定済みブロック計画です。 */
public record DungeonBlockPlan(
        @NotNull List<Placement> placements,
        @NotNull Map<Integer, List<Position>> gateBlocksByConnection,
        @NotNull Map<Integer, List<Position>> gateBarrierBlocksByConnection,
        @NotNull Map<Integer, RoomEntrance> roomEntrancesByRoom,
        @NotNull Map<Integer, List<Position>> spawnPointsByRoom,
        @NotNull Position playerSpawn
) {
    public DungeonBlockPlan {
        placements = List.copyOf(placements);
        gateBlocksByConnection = Map.copyOf(gateBlocksByConnection);
        gateBarrierBlocksByConnection = Map.copyOf(gateBarrierBlocksByConnection);
        roomEntrancesByRoom = Map.copyOf(roomEntrancesByRoom);
        spawnPointsByRoom = Map.copyOf(spawnPointsByRoom);
    }

    /** ACTIVE 中だけ閉鎖する子部屋境界と、通路側からの後続入室に使う座標です。 */
    public record RoomEntrance(
            @NotNull List<Position> gateBlocks,
            @NotNull List<Position> corridorApproachBlocks,
            @NotNull Position roomDestination
    ) {
        public RoomEntrance {
            gateBlocks = List.copyOf(gateBlocks);
            corridorApproachBlocks = List.copyOf(corridorApproachBlocks);
        }
    }

    /** ワールド内の整数ブロック座標です。 */
    public record Position(int x, int y, int z) {
    }

    /** 階段装飾の向きです。 */
    public enum Facing {
        NORTH,
        SOUTH,
        EAST,
        WEST
    }

    /** 1 ブロック分の最終配置です。stair は階段以外なら null です。 */
    public record Placement(
            @NotNull Position position,
            @NotNull Material material,
            @Nullable Stair stair
    ) {
    }

    /** 階段 BlockData へ適用する最小情報です。 */
    public record Stair(@NotNull Facing facing, boolean topHalf) {
    }
}
