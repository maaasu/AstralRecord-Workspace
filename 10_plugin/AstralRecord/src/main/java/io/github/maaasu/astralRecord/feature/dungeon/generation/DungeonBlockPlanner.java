package io.github.maaasu.astralRecord.feature.dungeon.generation;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonBlockPlan;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomType;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/** 配置計画を、重み付きテーマを適用したブロック列へ変換します。 */
public final class DungeonBlockPlanner {
    private static final long BLOCK_RANDOM_SALT = 0xBB67AE8584CAA73BL;
    private static final long DECORATION_RANDOM_SALT = 0x510E527FADE682D1L;
    private static final int LIGHT_SPACING = 6;

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
            decorateRoom(blocks, room, definition, layout);
        }

        Set<DungeonLayout.Point> corridorFootprint = createCorridorFootprint(
                layout.connections(),
                definition.generation().corridorWidth()
        );
        buildCorridors(blocks, corridorFootprint, layout.rooms(), definition, layout, random);

        Map<Integer, List<DungeonBlockPlan.Position>> gates = new LinkedHashMap<>();
        Map<Integer, List<DungeonBlockPlan.Position>> gateBarriers = new LinkedHashMap<>();
        for (DungeonLayout.Connection connection : layout.connections()) {
            DungeonLayout.Room parent = roomById.get(connection.fromRoomId());
            Set<DungeonLayout.Point> ownCorridorFootprint = createCorridorFootprint(
                    List.of(connection), definition.generation().corridorWidth());
            Set<DungeonLayout.Point> otherCorridorFootprint = createCorridorFootprint(
                    layout.connections().stream()
                            .filter(candidate -> candidate.id() != connection.id())
                            .toList(),
                    definition.generation().corridorWidth()
            );
            GateBlocks gate = buildGate(
                    blocks,
                    parent,
                    connection,
                    ownCorridorFootprint,
                    otherCorridorFootprint,
                    definition,
                    layout
            );
            gates.put(connection.id(), gate.visualBlocks());
            gateBarriers.put(connection.id(), gate.barrierBlocks());
        }

        Map<Integer, DungeonBlockPlan.RoomEntrance> roomEntrances = new LinkedHashMap<>();
        for (DungeonLayout.Connection connection : layout.connections()) {
            DungeonLayout.Room child = roomById.get(connection.toRoomId());
            roomEntrances.put(child.id(), buildRoomEntrance(
                    blocks,
                    child,
                    connection,
                    corridorFootprint,
                    gates.get(connection.id()),
                    gateBarriers.get(connection.id()),
                    definition,
                    layout
            ));
        }

        for (DungeonLayout.Room room : layout.rooms()) {
            if (shouldBuildPillar(definition.theme().pillar(), layout.seed(), room.id())) {
                buildPillar(blocks, room, definition, layout);
            }
        }
        buildRoomLighting(blocks, layout.rooms(), layout, definition.theme().lightMaterial());
        buildCorridorLighting(
                blocks, layout.connections(), gates, roomEntrances,
                layout, definition.theme().lightMaterial());

        Map<Integer, List<DungeonBlockPlan.Position>> spawnPoints = new LinkedHashMap<>();
        for (DungeonLayout.Room room : layout.rooms()) {
            boolean pillar = shouldBuildPillar(definition.theme().pillar(), layout.seed(), room.id());
            spawnPoints.put(room.id(), createSpawnPoints(blocks, room, layout.baseY() + 1, pillar));
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
                immutableLists(gateBarriers),
                Map.copyOf(roomEntrances),
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

    /** 部屋タイプに応じた装飾を、中央導線を維持したまま決定的に配置します。 */
    private void decorateRoom(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            @NotNull DungeonLayout.Room room,
            @NotNull DungeonDefinition definition,
            @NotNull DungeonLayout layout
    ) {
        SplittableRandom random = new SplittableRandom(
                layout.seed() ^ (DECORATION_RANDOM_SALT * (room.id() + 1L)));
        switch (room.type()) {
            case STANDARD -> {
            }
            case SUPPORT_HALL -> buildSupportHall(blocks, room, definition, layout);
            case COLLAPSED -> buildCollapsedRoom(blocks, room, definition, layout, random);
            case ORE_CHAMBER -> buildOreChamber(blocks, room, definition, layout, random);
        }
    }

    private void buildSupportHall(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            @NotNull DungeonLayout.Room room,
            @NotNull DungeonDefinition definition,
            @NotNull DungeonLayout layout
    ) {
        int floorY = layout.baseY();
        int ceilingY = floorY + layout.roomHeight() - 1;
        DungeonDefinition.Decorations decorations = definition.theme().decorations();
        for (int x = room.bounds().minX(); x <= room.bounds().maxX(); x++) {
            for (int z = room.bounds().minZ(); z <= room.bounds().maxZ(); z++) {
                if (!contains(room, x, z)) {
                    continue;
                }
                if (isBoundary(room, x, z)
                        && Math.floorMod(x * 31 + z * 17 + room.id(), 5) == 0) {
                    for (int y = floorY + 1; y < ceilingY; y++) {
                        put(blocks, x, y, z, decorations.supportMaterial());
                    }
                }
                if ((x == room.bounds().centerX() || z == room.bounds().centerZ())
                        && !isBoundary(room, x, z)) {
                    put(blocks, x, ceilingY, z, decorations.beamMaterial());
                }
            }
        }
    }

    private void buildCollapsedRoom(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            @NotNull DungeonLayout.Room room,
            @NotNull DungeonDefinition definition,
            @NotNull DungeonLayout layout,
            @NotNull SplittableRandom random
    ) {
        int centerX = room.bounds().centerX();
        int centerZ = room.bounds().centerZ();
        int clearHalfWidth = definition.generation().corridorWidth() / 2 + 1;
        int y = layout.baseY() + 1;
        for (int x = room.bounds().minX() + 1; x < room.bounds().maxX(); x++) {
            for (int z = room.bounds().minZ() + 1; z < room.bounds().maxZ(); z++) {
                if (!contains(room, x, z)
                        || Math.abs(x - centerX) <= clearHalfWidth
                        || Math.abs(z - centerZ) <= clearHalfWidth
                        || random.nextDouble() >= 0.16D) {
                    continue;
                }
                DungeonBlockPlan.Position position = new DungeonBlockPlan.Position(x, y, z);
                DungeonBlockPlan.Placement current = blocks.get(position);
                if (current != null && current.material().isAir()) {
                    put(blocks, position, choose(definition.theme().decorations().rubble(), random), null);
                }
            }
        }
    }

    private void buildOreChamber(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            @NotNull DungeonLayout.Room room,
            @NotNull DungeonDefinition definition,
            @NotNull DungeonLayout layout,
            @NotNull SplittableRandom random
    ) {
        int floorY = layout.baseY();
        int ceilingY = floorY + layout.roomHeight() - 1;
        for (int x = room.bounds().minX(); x <= room.bounds().maxX(); x++) {
            for (int z = room.bounds().minZ(); z <= room.bounds().maxZ(); z++) {
                if (!contains(room, x, z) || !isBoundary(room, x, z)) {
                    continue;
                }
                for (int y = floorY + 1; y < ceilingY; y++) {
                    if (random.nextDouble() < 0.14D) {
                        put(blocks, x, y, z, choose(definition.theme().decorations().accent(), random));
                    }
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

    /**
     * 見た目のゲート面と、その通路奥側にある通行遮断用バリア面を生成します。
     *
     * @param blocks 確定途中のブロック配置
     * @param parent 接続元の親部屋
     * @param connection 親子部屋の接続
     * @param ownCorridorFootprint 対象接続の通路 footprint
     * @param otherCorridorFootprint 他接続の通路 footprint
     * @param definition ダンジョン定義
     * @param layout ダンジョン配置
     * @return 表示ゲートと通行遮断バリアの座標
     */
    private @NotNull GateBlocks buildGate(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            @NotNull DungeonLayout.Room parent,
            @NotNull DungeonLayout.Connection connection,
            @NotNull Set<DungeonLayout.Point> ownCorridorFootprint,
            @NotNull Set<DungeonLayout.Point> otherCorridorFootprint,
            @NotNull DungeonDefinition definition,
            @NotNull DungeonLayout layout
    ) {
        List<DungeonLayout.Point> line = connection.centerLine();
        int exitIndex = -1;
        for (int index = 1; index < line.size(); index++) {
            DungeonLayout.Point previous = line.get(index - 1);
            DungeonLayout.Point current = line.get(index);
            if (contains(parent, previous.x(), previous.z()) && !contains(parent, current.x(), current.z())) {
                exitIndex = index;
                break;
            }
        }
        if (exitIndex < 0) {
            throw new IllegalStateException("Could not resolve gate for connection " + connection.id());
        }

        GatePlane gatePlane = null;
        for (int index = exitIndex; index < line.size(); index++) {
            DungeonLayout.Point previous = line.get(index - 1);
            DungeonLayout.Point current = line.get(index);
            int dx = Integer.compare(current.x(), previous.x());
            int dz = Integer.compare(current.z(), previous.z());
            List<DungeonLayout.Point> visual = gatePlane(current, dx, dz,
                    definition.generation().corridorWidth());
            List<DungeonLayout.Point> barrier = visual.stream()
                    .map(point -> new DungeonLayout.Point(point.x() + dx, point.z() + dz))
                    .toList();
            boolean insideRoom = java.util.stream.Stream.concat(visual.stream(), barrier.stream())
                    .anyMatch(point -> isInsideAnyRoom(
                            layout.rooms(), point.x(), point.z()));
            boolean outsideOwnCorridor = java.util.stream.Stream.concat(
                            visual.stream(), barrier.stream())
                    .anyMatch(point -> !ownCorridorFootprint.contains(point));
            boolean overlapsOtherCorridor = java.util.stream.Stream.concat(
                            visual.stream(), barrier.stream())
                    .anyMatch(otherCorridorFootprint::contains);
            if (!insideRoom && !outsideOwnCorridor && !overlapsOtherCorridor) {
                gatePlane = new GatePlane(visual, barrier);
                break;
            }
        }
        if (gatePlane == null) {
            throw new IllegalStateException("Could not resolve isolated gate for connection "
                    + connection.id());
        }

        List<DungeonBlockPlan.Position> gateBlocks = new ArrayList<>();
        List<DungeonBlockPlan.Position> barrierBlocks = new ArrayList<>();
        for (int index = 0; index < gatePlane.visual().size(); index++) {
            DungeonLayout.Point visualPoint = gatePlane.visual().get(index);
            DungeonLayout.Point barrierPoint = gatePlane.barrier().get(index);
            for (int y = layout.baseY() + 1;
                 y <= layout.baseY() + definition.generation().corridorHeight();
                 y++) {
                DungeonBlockPlan.Position position = new DungeonBlockPlan.Position(
                        visualPoint.x(), y, visualPoint.z());
                put(blocks, position, definition.theme().gateMaterial(), null);
                gateBlocks.add(position);
                DungeonBlockPlan.Position barrierPosition = new DungeonBlockPlan.Position(
                        barrierPoint.x(), y, barrierPoint.z());
                put(blocks, barrierPosition, Material.BARRIER, null);
                barrierBlocks.add(barrierPosition);
            }
        }
        return new GateBlocks(List.copyOf(gateBlocks), List.copyOf(barrierBlocks));
    }

    private @NotNull List<DungeonLayout.Point> gatePlane(
            @NotNull DungeonLayout.Point center,
            int dx,
            int dz,
            int corridorWidth
    ) {
        int negative = (corridorWidth - 1) / 2;
        int positive = corridorWidth / 2;
        List<DungeonLayout.Point> plane = new ArrayList<>(corridorWidth);
        for (int offset = -negative; offset <= positive; offset++) {
            plane.add(new DungeonLayout.Point(
                    center.x() + (dz == 0 ? 0 : offset),
                    center.z() + (dx == 0 ? 0 : offset)
            ));
        }
        return List.copyOf(plane);
    }

    /**
     * 全接続の実通路 footprint と子部屋形状の歩行可能境界から、ACTIVE 中の入口閉鎖面を求めます。
     * 初期ブロック配置には含めず、部屋の ACTIVE 遷移時だけガラスとして配置します。
     *
     * @param blocks 確定途中のブロック配置
     * @param child 接続先の子部屋
     * @param connection 親部屋から子部屋へ向かう接続
     * @param corridorFootprint 全接続が実際に開削した通路 footprint
     * @param incomingGateBlocks 対象接続自身の進行ゲート座標
     * @param incomingBarrierBlocks 対象接続自身の進行バリア座標
     * @param definition 通路幅・高さを持つダンジョン定義
     * @param layout 基準Yを持つ配置
     * @return 子部屋境界、通路側接近面、部屋側着地点
     */
    private @NotNull DungeonBlockPlan.RoomEntrance buildRoomEntrance(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            @NotNull DungeonLayout.Room child,
            @NotNull DungeonLayout.Connection connection,
            @NotNull Set<DungeonLayout.Point> corridorFootprint,
            @NotNull List<DungeonBlockPlan.Position> incomingGateBlocks,
            @NotNull List<DungeonBlockPlan.Position> incomingBarrierBlocks,
            @NotNull DungeonDefinition definition,
            @NotNull DungeonLayout layout
    ) {
        List<DungeonLayout.Point> line = connection.centerLine();
        DungeonLayout.Point roomCenter = null;
        DungeonLayout.Point corridorCenter = line.getFirst();
        for (int index = 1; index < line.size(); index++) {
            DungeonLayout.Point current = line.get(index);
            if (!contains(child, corridorCenter.x(), corridorCenter.z())
                    && contains(child, current.x(), current.z())) {
                roomCenter = current;
                break;
            }
            corridorCenter = current;
        }
        if (roomCenter == null) {
            throw new IllegalStateException("Could not resolve room entrance for connection " + connection.id());
        }

        int walkY = layout.baseY() + 1;
        Set<DungeonBlockPlan.Position> releasedIncomingBlocks = new HashSet<>();
        incomingGateBlocks.stream()
                .filter(position -> position.y() == walkY)
                .forEach(releasedIncomingBlocks::add);
        incomingBarrierBlocks.stream()
                .filter(position -> position.y() == walkY)
                .forEach(releasedIncomingBlocks::add);
        Set<DungeonLayout.Point> gateFootprint = new LinkedHashSet<>();
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (DungeonLayout.Point point : corridorFootprint) {
            if (contains(child, point.x(), point.z())
                    || !isWalkable(
                    blocks, point.x(), walkY, point.z(), releasedIncomingBlocks)) {
                continue;
            }
            for (int[] direction : directions) {
                int neighborX = point.x() + direction[0];
                int neighborZ = point.z() + direction[1];
                if (contains(child, neighborX, neighborZ)
                        && isWalkable(
                        blocks, neighborX, walkY, neighborZ, releasedIncomingBlocks)) {
                    gateFootprint.add(point);
                    break;
                }
            }
        }
        if (gateFootprint.isEmpty()) {
            throw new IllegalStateException("Could not resolve open room entrance for connection "
                    + connection.id());
        }

        List<DungeonBlockPlan.Position> gateBlocks = new ArrayList<>();
        for (DungeonLayout.Point point : gateFootprint) {
            for (int y = layout.baseY() + 1;
                 y <= layout.baseY() + definition.generation().corridorHeight();
                 y++) {
                gateBlocks.add(new DungeonBlockPlan.Position(point.x(), y, point.z()));
            }
        }

        Set<DungeonBlockPlan.Position> corridorApproachBlocks = new LinkedHashSet<>();
        List<DungeonLayout.Point> roomSideCandidates = new ArrayList<>();
        for (DungeonLayout.Point gatePoint : gateFootprint) {
            for (int[] direction : directions) {
                int neighborX = gatePoint.x() + direction[0];
                int neighborZ = gatePoint.z() + direction[1];
                DungeonLayout.Point neighbor = new DungeonLayout.Point(neighborX, neighborZ);
                if (!isWalkable(
                        blocks, neighborX, walkY, neighborZ, releasedIncomingBlocks)) {
                    continue;
                }
                if (contains(child, neighborX, neighborZ)) {
                    roomSideCandidates.add(neighbor);
                } else if (!gateFootprint.contains(neighbor)) {
                    corridorApproachBlocks.add(new DungeonBlockPlan.Position(
                            neighborX, walkY, neighborZ));
                }
            }
        }
        if (corridorApproachBlocks.isEmpty() || roomSideCandidates.isEmpty()) {
            throw new IllegalStateException("Could not resolve both sides of room entrance for connection "
                    + connection.id());
        }
        DungeonLayout.Point resolvedRoomCenter = roomCenter;
        DungeonLayout.Point destination = roomSideCandidates.stream()
                .min(Comparator
                        .comparingInt((DungeonLayout.Point point) ->
                                Math.abs(point.x() - resolvedRoomCenter.x())
                                        + Math.abs(point.z() - resolvedRoomCenter.z()))
                        .thenComparingInt(DungeonLayout.Point::x)
                        .thenComparingInt(DungeonLayout.Point::z))
                .orElseThrow();
        return new DungeonBlockPlan.RoomEntrance(
                gateBlocks,
                List.copyOf(corridorApproachBlocks),
                new DungeonBlockPlan.Position(
                        destination.x(),
                        walkY,
                        destination.z())
        );
    }

    private boolean isWalkable(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            int x,
            int y,
            int z,
            @NotNull Set<DungeonBlockPlan.Position> releasedIncomingBlocks
    ) {
        DungeonBlockPlan.Position position = new DungeonBlockPlan.Position(x, y, z);
        if (releasedIncomingBlocks.contains(position)) {
            return true;
        }
        DungeonBlockPlan.Placement placement = blocks.get(position);
        return placement != null && placement.material().isAir();
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

    /**
     * 各部屋の中央を基準に一定間隔で床置きのたいまつを配置します。
     *
     * @param blocks 確定途中のブロック配置
     * @param rooms 部屋一覧
     * @param layout ダンジョン配置
     */
    private void buildRoomLighting(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            @NotNull List<DungeonLayout.Room> rooms,
            @NotNull DungeonLayout layout,
            @NotNull Material lightMaterial
    ) {
        for (DungeonLayout.Room room : rooms) {
            int centerX = room.bounds().centerX();
            int centerZ = room.bounds().centerZ();
            boolean placed = false;
            for (int x = room.bounds().minX() + 2; x <= room.bounds().maxX() - 2; x++) {
                if (Math.floorMod(x - centerX, LIGHT_SPACING) != 0) {
                    continue;
                }
                for (int z = room.bounds().minZ() + 2; z <= room.bounds().maxZ() - 2; z++) {
                    if (Math.floorMod(z - centerZ, LIGHT_SPACING) == 0 && contains(room, x, z)) {
                        placed |= placeFloorLight(
                                blocks,
                                new DungeonBlockPlan.Position(x, layout.baseY() + 1, z),
                                lightMaterial);
                    }
                }
            }
            if (!placed) {
                placeNearestRoomLight(blocks, room, layout, lightMaterial);
            }
        }
    }

    /**
     * 中央基準の候補へ配置できなかった部屋で、中心に近い歩行可能な床から順にたいまつを配置します。
     *
     * @param blocks 確定途中のブロック配置
     * @param room 照明を補完する部屋
     * @param layout ダンジョン配置。部屋の床上座標を解決できることが前提です
     * @return たいまつを配置できた場合は {@code true}、配置可能な床がない場合は {@code false}
     * @implNote 配置に成功した座標をたいまつへ置き換え、以後の Mob 出現候補から除外される副作用があります。
     */
    private boolean placeNearestRoomLight(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            @NotNull DungeonLayout.Room room,
            @NotNull DungeonLayout layout,
            @NotNull Material lightMaterial
    ) {
        int centerX = room.bounds().centerX();
        int centerZ = room.bounds().centerZ();
        List<DungeonBlockPlan.Position> candidates = new ArrayList<>();
        for (int x = room.bounds().minX() + 2; x <= room.bounds().maxX() - 2; x++) {
            for (int z = room.bounds().minZ() + 2; z <= room.bounds().maxZ() - 2; z++) {
                if (contains(room, x, z)) {
                    candidates.add(new DungeonBlockPlan.Position(x, layout.baseY() + 1, z));
                }
            }
        }
        candidates.sort(Comparator
                .comparingInt((DungeonBlockPlan.Position position) ->
                        Math.abs(position.x() - centerX) + Math.abs(position.z() - centerZ))
                .thenComparingInt(DungeonBlockPlan.Position::x)
                .thenComparingInt(DungeonBlockPlan.Position::z));
        for (DungeonBlockPlan.Position candidate : candidates) {
            if (placeFloorLight(blocks, candidate, lightMaterial)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 各通路の中心線へ一定間隔で床置きのたいまつを配置します。
     *
     * @param blocks 確定途中のブロック配置
     * @param connections 通路接続一覧
     * @param gates 接続ごとの閉鎖ゲート座標
     * @param roomEntrances ACTIVE中に閉鎖する子部屋入口
     * @param layout ダンジョン配置
     */
    private void buildCorridorLighting(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            @NotNull List<DungeonLayout.Connection> connections,
            @NotNull Map<Integer, List<DungeonBlockPlan.Position>> gates,
            @NotNull Map<Integer, DungeonBlockPlan.RoomEntrance> roomEntrances,
            @NotNull DungeonLayout layout,
            @NotNull Material lightMaterial
    ) {
        Set<DungeonBlockPlan.Position> gatePositions = new LinkedHashSet<>();
        gates.values().forEach(gatePositions::addAll);
        roomEntrances.values().stream()
                .map(DungeonBlockPlan.RoomEntrance::gateBlocks)
                .forEach(gatePositions::addAll);
        for (DungeonLayout.Connection connection : connections) {
            List<DungeonLayout.Point> line = connection.centerLine();
            boolean placed = false;
            for (int index = LIGHT_SPACING / 2; index < line.size(); index += LIGHT_SPACING) {
                DungeonLayout.Point point = line.get(index);
                placed |= placeCorridorLight(blocks, gatePositions, layout, point, lightMaterial);
            }
            if (!placed) {
                placeCorridorLight(
                        blocks, gatePositions, layout, line.get(line.size() / 2), lightMaterial);
            }
        }
    }

    /**
     * 通路の中心線候補へ、閉鎖ゲートを避けて床置きのたいまつを配置します。
     *
     * @param blocks 確定途中のブロック配置
     * @param gatePositions 閉鎖ゲートの座標集合
     * @param layout ダンジョン配置。通路点の床上座標を解決できることが前提です
     * @param point 照明候補となる通路中心線上の座標
     * @return ゲート以外の配置可能な床へたいまつを置けた場合は {@code true}、それ以外は {@code false}
     * @implNote 配置に成功した座標をたいまつへ置き換えます。
     */
    private boolean placeCorridorLight(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            @NotNull Set<DungeonBlockPlan.Position> gatePositions,
            @NotNull DungeonLayout layout,
            @NotNull DungeonLayout.Point point,
            @NotNull Material lightMaterial
    ) {
        DungeonBlockPlan.Position position = new DungeonBlockPlan.Position(
                point.x(), layout.baseY() + 1, point.z());
        if (gatePositions.contains(position)) {
            return false;
        }
        return placeFloorLight(blocks, position, lightMaterial);
    }

    /**
     * 床と上方空間が確保された候補座標へ床置きのたいまつを配置します。
     *
     * @param blocks 確定途中のブロック配置
     * @param position たいまつを置く床上座標。対象・支持床・上方空間が配置済みであることが前提です
     * @return 対象と上方が空気かつ支持床が空気以外で、たいまつを置けた場合は {@code true}、それ以外は {@code false}
     * @implNote 配置に成功した場合だけ対象座標のブロックをたいまつへ置き換えます。
     */
    private boolean placeFloorLight(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            @NotNull DungeonBlockPlan.Position position,
            @NotNull Material lightMaterial
    ) {
        DungeonBlockPlan.Placement target = blocks.get(position);
        DungeonBlockPlan.Placement support = blocks.get(new DungeonBlockPlan.Position(
                position.x(), position.y() - 1, position.z()));
        DungeonBlockPlan.Placement above = blocks.get(new DungeonBlockPlan.Position(
                position.x(), position.y() + 1, position.z()));
        if (target == null || !target.material().isAir()
                || support == null || support.material().isAir()
                || above == null || !above.material().isAir()) {
            return false;
        }
        put(blocks, position, lightMaterial, null);
        return true;
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
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
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
                DungeonBlockPlan.Position position = new DungeonBlockPlan.Position(x, y, z);
                DungeonBlockPlan.Placement placement = blocks.get(position);
                if (placement != null && placement.material().isAir()) {
                    candidates.add(position);
                }
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

    /** 他接続の通路と重ならない表示ゲート面とバリア面です。 */
    private record GatePlane(
            @NotNull List<DungeonLayout.Point> visual,
            @NotNull List<DungeonLayout.Point> barrier
    ) {
    }

    /** 閉鎖中に表示するゲート面と、通行を確実に遮断する不可視バリア面です。 */
    private record GateBlocks(
            @NotNull List<DungeonBlockPlan.Position> visualBlocks,
            @NotNull List<DungeonBlockPlan.Position> barrierBlocks
    ) {
    }
}
