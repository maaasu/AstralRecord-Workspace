package io.github.maaasu.astralRecord.feature.dungeon.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/** seed から決定した、ブロックを持たない純粋なダンジョン配置計画です。 */
public record DungeonLayout(
        long seed,
        int areaWidth,
        int areaDepth,
        int baseY,
        int roomHeight,
        @NotNull List<Room> rooms,
        @NotNull List<Connection> connections,
        int startRoomId,
        int bossRoomId
) {
    public DungeonLayout {
        rooms = List.copyOf(rooms);
        connections = List.copyOf(connections);
    }

    /** 部屋の進行上の役割です。 */
    public enum RoomRole {
        START,
        NORMAL,
        BOSS
    }

    /** X/Z 平面上の両端を含む矩形です。 */
    public record Rect(int minX, int minZ, int maxX, int maxZ) {
        public int width() {
            return maxX - minX + 1;
        }

        public int depth() {
            return maxZ - minZ + 1;
        }

        public int centerX() {
            return (minX + maxX) / 2;
        }

        public int centerZ() {
            return (minZ + maxZ) / 2;
        }

        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        public boolean intersects(@NotNull Rect other) {
            return minX <= other.maxX && maxX >= other.minX
                    && minZ <= other.maxZ && maxZ >= other.minZ;
        }
    }

    /** 生成済み部屋です。 */
    public record Room(
            int id,
            @NotNull Rect bounds,
            @NotNull DungeonRoomShape shape,
            @NotNull DungeonRoomType type,
            @NotNull RoomRole role,
            int distanceFromStart
    ) {
        /** 部屋タイプ導入前の呼び出し元向け互換コンストラクタです。 */
        public Room(
                int id,
                @NotNull Rect bounds,
                @NotNull DungeonRoomShape shape,
                @NotNull RoomRole role,
                int distanceFromStart
        ) {
            this(id, bounds, shape, DungeonRoomType.STANDARD, role, distanceFromStart);
        }
    }

    /** 直交通路の中心線上の座標です。 */
    public record Point(int x, int z) {
    }

    /** 親部屋から子部屋へ向かう進行方向付き接続です。 */
    public record Connection(
            int id,
            int fromRoomId,
            int toRoomId,
            @NotNull List<Point> centerLine
    ) {
        public Connection {
            centerLine = List.copyOf(centerLine);
        }
    }
}
