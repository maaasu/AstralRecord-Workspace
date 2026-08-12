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
            GateBlocks gate = buildGate(
                    blocks,
                    parent,
                    connection,
                    definition,
                    layout
            );
            gates.put(connection.id(), gate.visualBlocks());
            gateBarriers.put(connection.id(), gate.barrierBlocks());
        }

        for (DungeonLayout.Room room : layout.rooms()) {
            if (shouldBuildPillar(definition.theme().pillar(), layout.seed(), room.id())) {
                buildPillar(blocks, room, definition, layout);
            }
        }
        buildRoomLighting(blocks, layout.rooms(), layout, definition.theme().lightMaterial());
        buildCorridorLighting(
                blocks, layout.connections(), gates, layout, definition.theme().lightMaterial());

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
     * @param definition ダンジョン定義
     * @param layout ダンジョン配置
     * @return 表示ゲートと通行遮断バリアの座標
     */
    private @NotNull GateBlocks buildGate(
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
        List<DungeonBlockPlan.Position> barrierBlocks = new ArrayList<>();
        for (int offset = -negative; offset <= positive; offset++) {
            int x = gateCenter.x() + (dz == 0 ? 0 : offset);
            int z = gateCenter.z() + (dx == 0 ? 0 : offset);
            for (int y = layout.baseY() + 1;
                 y <= layout.baseY() + definition.generation().corridorHeight();
                 y++) {
                DungeonBlockPlan.Position position = new DungeonBlockPlan.Position(x, y, z);
                put(blocks, position, definition.theme().gateMaterial(), null);
                gateBlocks.add(position);
                DungeonBlockPlan.Position barrierPosition = new DungeonBlockPlan.Position(x + dx, y, z + dz);
                put(blocks, barrierPosition, Material.BARRIER, null);
                barrierBlocks.add(barrierPosition);
            }
        }
        return new GateBlocks(List.copyOf(gateBlocks), List.copyOf(barrierBlocks));
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
     * @param layout ダンジョン配置
     */
    private void buildCorridorLighting(
            @NotNull Map<DungeonBlockPlan.Position, DungeonBlockPlan.Placement> blocks,
            @NotNull List<DungeonLayout.Connection> connections,
            @NotNull Map<Integer, List<DungeonBlockPlan.Position>> gates,
            @NotNull DungeonLayout layout,
            @NotNull Material lightMaterial
    ) {
        Set<DungeonBlockPlan.Position> gatePositions = new LinkedHashSet<>();
        gates.values().forEach(gatePositions::addAll);
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

    /** 閉鎖中に表示するゲート面と、通行を確実に遮断する不可視バリア面です。 */
    private record GateBlocks(
            @NotNull List<DungeonBlockPlan.Position> visualBlocks,
            @NotNull List<DungeonBlockPlan.Position> barrierBlocks
    ) {
    }
}
