package io.github.maaasu.astralRecord.feature.dungeon.generation;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonBlockPlan;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/** 配置計画を、重み付きテーマを適用したブロック列へ変換します。 */
public final class DungeonBlockPlanner {
    private static final long BLOCK_RANDOM_SALT = 0xBB67AE8584CAA73BL;

    /**
     * ブロック計画を生成します。Bukkit ワールドには触れないため非同期実行できます。
     *
     * @param definition ダンジョン定義
     * @param layout BSP 配置
     * @return ブロック計画
     */
    public @NotNull DungeonBlockPlan plan(
            @NotNull DungeonDefinition definition,
            @NotNull DungeonLayout layout
    ) {
        SplittableRandom random = new SplittableRandom(layout.seed() ^ BLOCK_RANDOM_SALT);
        Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks = new LinkedHashMap<>();
        Map<Integer, DungeonLayout.Room> roomById = new HashMap<>();
        for (DungeonLayout.Room room : layout.rooms()) {
            roomById.put(room.id(), room);
            buildRoom(blocks, room, definition, layout, random);
        }

        Set<DungeonLayout.Point> corridorFootprint = createCorridorFootprint(
                layout.connections(),
                definition.generation().corridorWidth()
        );
        buildCorridors(blocks, corridorFootprint, layout.rooms(), definition, layout, random);

        Map<Integer, List<DungeonBlockPlan.Position>> gates = new LinkedHashMap<>();
        for (DungeonLayout.Connection connection : layout.connections()) {
            DungeonLayout.Room parent = roomById.get(connection.fromRoomId());
            List<DungeonBlockPlan.Position> gate = buildGate(
                    blocks,
                    parent,
                    connection,
                    definition,
                    layout
            );
            gates.put(connection.id(), gate);
        }

        Map<Integer, List<DungeonBlockPlan.Position>> spawnPoints = new LinkedHashMap<>();
        for (DungeonLayout.Room room : layout.rooms()) {
            boolean pillar = shouldBuildPillar(definition.theme().pillar(), layout.seed(), room.id());
            if (pillar) {
                buildPillar(blocks, room, definition, layout);
            }
            spawnPoints.put(room.id(), createSpawnPoints(room, layout.baseY() + 1, pillar));
        }

        DungeonLayout.Room startRoom = roomById.get(layout.startRoomId());
        List<DungeonBlockPlan.Position> startPoints = spawnPoints.get(startRoom.id());
        DungeonBlockPlan.Position playerSpawn = startPoints.isEmpty()
                ? new DungeonBlockPlan.Position(
                        startRoom.bounds().centerX(), layout.baseY() + 1, startRoom.bounds().centerZ())
                : startPoints.getFirst();

        return new DungeonBlockPlan(
                List.copyOf(blocks.values()),
                immutableLists(gates),
                immutableLists(spawnPoints),
                playerSpawn
        );
    }

    private void buildRoom(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            @NotNull DungeonLayout.Room room,
            @NotNull DungeonDefinition definition,
            @NotNull DungeonLayout layout,
            @NotNull SplittableRandom random
    ) {
        int floorY = layout.baseY();
        int ceilingY = floorY + layout.roomHeight() - 1;
        DungeonLayout.Rect bounds = room.bounds();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                if (!contains(room, x, z)) {
                    continue;
                }
                put(blocks, x, floorY, z, choose(definition.theme().floor(), random));
                put(blocks, x, ceilingY, z, choose(definition.theme().ceiling(), random));
                boolean boundary = isBoundary(room, x, z);
                for (int y = floorY + 1; y < ceilingY; y++) {
                    put(blocks, x, y, z,
                            boundary ? choose(definition.theme().wall(), random) : Material.AIR);
                }
            }
        }
    }

    private @NotNull Set<DungeonLayout.Point> createCorridorFootprint(
            @NotNull List<DungeonLayout.Connection> connections,
            int corridorWidth
    ) {
        int negative = (corridorWidth - 1) / 2;
        int positive = corridorWidth / 2;
        Set<DungeonLayout.Point> footprint = new LinkedHashSet<>();
        for (DungeonLayout.Connection connection : connections) {
            for (DungeonLayout.Point point : connection.centerLine()) {
                for (int dx = -negative; dx <= positive; dx++) {
                    for (int dz = -negative; dz <= positive; dz++) {
                        footprint.add(new DungeonLayout.Point(point.x() + dx, point.z() + dz));
                    }
                }
            }
        }
        return footprint;
    }

    private void buildCorridors(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            @NotNull Set<DungeonLayout.Point> footprint,
            @NotNull List<DungeonLayout.Room> rooms,
            @NotNull DungeonDefinition definition,
            @NotNull DungeonLayout layout,
            @NotNull SplittableRandom random
    ) {
        int floorY = layout.baseY();
        int ceilingY = floorY + definition.generation().corridorHeight() + 1;
        for (DungeonLayout.Point point : footprint) {
            if (isDeepRoomInterior(rooms, point.x(), point.z())) {
                continue;
            }
            put(blocks, point.x(), floorY, point.z(), choose(definition.theme().corridor(), random));
            put(blocks, point.x(), ceilingY, point.z(), choose(definition.theme().corridor(), random));
            for (int y = floorY + 1; y < ceilingY; y++) {
                put(blocks, point.x(), y, point.z(), Material.AIR);
            }
        }

        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (DungeonLayout.Point point : footprint) {
            for (int[] direction : directions) {
                DungeonLayout.Point neighbor = new DungeonLayout.Point(
                        point.x() + direction[0], point.z() + direction[1]);
                if (footprint.contains(neighbor) || isInsideAnyRoom(rooms, neighbor.x(), neighbor.z())) {
                    continue;
                }
                for (int y = floorY + 1; y < ceilingY; y++) {
                    put(blocks, neighbor.x(), y, neighbor.z(), choose(definition.theme().corridor(), random));
                }
            }
        }
    }

    private @NotNull List<DungeonBlockPlan.Position> buildGate(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            @NotNull DungeonLayout.Room parent,
            @NotNull DungeonLayout.Connection connection,
            @NotNull DungeonDefinition definition,
            @NotNull DungeonLayout layout
    ) {
        List<DungeonLayout.Point> line = connection.centerLine();
        DungeonLayout.Point gateCenter = null;
        DungeonLayout.Point previous = line.getFirst();
        for (int index = 1; index < line.size(); index++) {
            DungeonLayout.Point current = line.get(index);
            if (contains(parent, previous.x(), previous.z()) && !contains(parent, current.x(), current.z())) {
                gateCenter = current;
                break;
            }
            previous = current;
        }
        if (gateCenter == null) {
            throw new IllegalStateException("Could not resolve gate for connection " + connection.id());
        }

        int dx = Integer.compare(gateCenter.x(), previous.x());
        int dz = Integer.compare(gateCenter.z(), previous.z());
        int negative = (definition.generation().corridorWidth() - 1) / 2;
        int positive = definition.generation().corridorWidth() / 2;
        List<DungeonBlockPlan.Position> gateBlocks = new ArrayList<>();
        for (int offset = -negative; offset <= positive; offset++) {
            int x = gateCenter.x() + (dz == 0 ? 0 : offset);
            int z = gateCenter.z() + (dx == 0 ? 0 : offset);
            for (int y = layout.baseY() + 1;
                 y <= layout.baseY() + definition.generation().corridorHeight();
                 y++) {
                DungeonBlockPlan.Position position = new DungeonBlockPlan.Position(x, y, z);
                put(blocks, position, definition.theme().gateMaterial(), null);
                gateBlocks.add(position);
            }
        }
        return List.copyOf(gateBlocks);
    }

    private boolean shouldBuildPillar(
            @NotNull DungeonDefinition.Pillar pillar,
            long seed,
            int roomId
    ) {
        if (!pillar.enabled() || pillar.chance() <= 0.0D) {
            return false;
        }
        SplittableRandom random = new SplittableRandom(seed ^ (0x3C6EF372FE94F82BL * (roomId + 1L)));
        return random.nextDouble() < pillar.chance();
    }

    private void buildPillar(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            @NotNull DungeonLayout.Room room,
            @NotNull DungeonDefinition definition,
            @NotNull DungeonLayout layout
    ) {
        int x = room.bounds().centerX();
        int z = room.bounds().centerZ();
        int floorY = layout.baseY();
        int ceilingY = floorY + layout.roomHeight() - 1;
        for (int y = floorY + 1; y < ceilingY; y++) {
            put(blocks, x, y, z, definition.theme().pillar().material());
        }

        addStair(blocks, x, floorY + 1, z - 1, definition, DungeonBlockPlan.Facing.NORTH, false);
        addStair(blocks, x, floorY + 1, z + 1, definition, DungeonBlockPlan.Facing.SOUTH, false);
        addStair(blocks, x + 1, floorY + 1, z, definition, DungeonBlockPlan.Facing.EAST, false);
        addStair(blocks, x - 1, floorY + 1, z, definition, DungeonBlockPlan.Facing.WEST, false);
        addStair(blocks, x, ceilingY - 1, z - 1, definition, DungeonBlockPlan.Facing.SOUTH, true);
        addStair(blocks, x, ceilingY - 1, z + 1, definition, DungeonBlockPlan.Facing.NORTH, true);
        addStair(blocks, x + 1, ceilingY - 1, z, definition, DungeonBlockPlan.Facing.WEST, true);
        addStair(blocks, x - 1, ceilingY - 1, z, definition, DungeonBlockPlan.Facing.EAST, true);
    }

    private void addStair(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            int x,
            int y,
            int z,
            @NotNull DungeonDefinition definition,
            @NotNull DungeonBlockPlan.Facing facing,
            boolean topHalf
    ) {
        put(blocks, new DungeonBlockPlan.Position(x, y, z),
                definition.theme().pillar().stairMaterial(),
                new DungeonBlockPlan.Stair(facing, topHalf));
    }

    private @NotNull List<DungeonBlockPlan.Position> createSpawnPoints(
            @NotNull DungeonLayout.Room room,
            int y,
            boolean pillar
    ) {
        int centerX = room.bounds().centerX();
        int centerZ = room.bounds().centerZ();
        List<DungeonBlockPlan.Position> candidates = new ArrayList<>();
        for (int x = room.bounds().minX() + 2; x <= room.bounds().maxX() - 2; x++) {
            for (int z = room.bounds().minZ() + 2; z <= room.bounds().maxZ() - 2; z++) {
                if (!contains(room, x, z)) {
                    continue;
                }
                int centerDistance = Math.abs(x - centerX) + Math.abs(z - centerZ);
                if (pillar && centerDistance <= 1) {
                    continue;
                }
                candidates.add(new DungeonBlockPlan.Position(x, y, z));
            }
        }
        candidates.sort(Comparator
                .comparingInt((DungeonBlockPlan.Position position) ->
                        Math.abs(position.x() - centerX) + Math.abs(position.z() - centerZ))
                .thenComparingInt(DungeonBlockPlan.Position::x)
                .thenComparingInt(DungeonBlockPlan.Position::z));
        if (candidates.isEmpty()) {
            candidates.add(new DungeonBlockPlan.Position(centerX, y, centerZ));
        }
        return List.copyOf(candidates);
    }

    private boolean isBoundary(@NotNull DungeonLayout.Room room, int x, int z) {
        return !contains(room, x + 1, z)
                || !contains(room, x - 1, z)
                || !contains(room, x, z + 1)
                || !contains(room, x, z - 1);
    }

    private boolean isDeepRoomInterior(@NotNull List<DungeonLayout.Room> rooms, int x, int z) {
        for (DungeonLayout.Room room : rooms) {
            if (contains(room, x, z) && !isBoundary(room, x, z)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInsideAnyRoom(@NotNull List<DungeonLayout.Room> rooms, int x, int z) {
        for (DungeonLayout.Room room : rooms) {
            if (contains(room, x, z)) {
                return true;
            }
        }
        return false;
    }

    private boolean contains(@NotNull DungeonLayout.Room room, int x, int z) {
        DungeonLayout.Rect bounds = room.bounds();
        if (!bounds.contains(x, z)) {
            return false;
        }
        if (room.shape() == DungeonRoomShape.RECTANGLE) {
            return true;
        }
        double radiusX = Math.max(1.0D, (bounds.width() - 1) / 2.0D);
        double radiusZ = Math.max(1.0D, (bounds.depth() - 1) / 2.0D);
        double normalizedX = (x - (bounds.minX() + bounds.maxX()) / 2.0D) / radiusX;
        double normalizedZ = (z - (bounds.minZ() + bounds.maxZ()) / 2.0D) / radiusZ;
        return normalizedX * normalizedX + normalizedZ * normalizedZ <= 1.0D;
    }

    private @NotNull Material choose(
            @NotNull List<DungeonDefinition.WeightedMaterial> palette,
            @NotNull SplittableRandom random
    ) {
        int total = palette.stream().mapToInt(DungeonDefinition.WeightedMaterial::weight).sum();
        int roll = random.nextInt(total);
        for (DungeonDefinition.WeightedMaterial entry : palette) {
            roll -= entry.weight();
            if (roll < 0) {
                return entry.material();
            }
        }
        return palette.getLast().material();
    }

    private void put(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            int x,
            int y,
            int z,
            @NotNull Material material
    ) {
        put(blocks, new DungeonBlockPlan.Position(x, y, z), material, null);
    }

    private void put(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            @NotNull DungeonBlockPlan.Position position,
            @NotNull Material material,
            DungeonBlockPlan.Stair stair
    ) {
        blocks.put(position, new DungeonBlockPlan.Placement(position, material, stair));
    }

    private <K> @NotNull Map<K, List<DungeonBlockPlan.Position>> immutableLists(
            @NotNull Map<K, List<DungeonBlockPlan.Position>> source
    ) {
        Map<K, List<DungeonBlockPlan.Position>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }
}
