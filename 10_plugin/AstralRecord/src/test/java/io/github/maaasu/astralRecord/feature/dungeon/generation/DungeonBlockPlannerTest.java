package io.github.maaasu.astralRecord.feature.dungeon.generation;

import io.github.maaasu.astralRecord.feature.dungeon.DungeonTestFixtures;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonBlockPlan;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonBlockPlannerTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 2. ブロック生成
     * 検証契約: 各接続の親部屋側出口に通路幅掛ける通路高さ個のgateMaterial座標を生成する。
     */
    @Test
    void createsAFullGatePlaneForEveryConnection() {
        DungeonDefinition definition = DungeonTestFixtures.definition();
        DungeonLayout layout = new DungeonLayoutPlanner().plan(definition, 112233L);
        DungeonBlockPlan plan = new DungeonBlockPlanner().plan(definition, layout);

        assertEquals(layout.connections().size(), plan.gateBlocksByConnection().size());
        assertEquals(layout.connections().size(), plan.gateBarrierBlocksByConnection().size());
        int expectedGateBlocks = definition.generation().corridorWidth()
                * definition.generation().corridorHeight();
        for (DungeonLayout.Connection connection : layout.connections()) {
            List<DungeonBlockPlan.Position> gate = plan.gateBlocksByConnection().get(connection.id());
            List<DungeonBlockPlan.Position> barriers = plan.gateBarrierBlocksByConnection().get(connection.id());
            assertNotNull(gate);
            assertNotNull(barriers);
            assertEquals(expectedGateBlocks, gate.size());
            assertEquals(expectedGateBlocks, barriers.size());
            for (DungeonBlockPlan.Position position : gate) {
                Material finalMaterial = plan.placements().stream()
                        .filter(placement -> placement.position().equals(position))
                        .findFirst()
                        .orElseThrow()
                        .material();
                assertEquals(definition.theme().gateMaterial(), finalMaterial);
            }
            for (int index = 0; index < gate.size(); index++) {
                DungeonBlockPlan.Position visual = gate.get(index);
                DungeonBlockPlan.Position barrier = barriers.get(index);
                Material finalMaterial = plan.placements().stream()
                        .filter(placement -> placement.position().equals(barrier))
                        .findFirst()
                        .orElseThrow()
                        .material();
                assertEquals(Material.BARRIER, finalMaterial);
                assertEquals(1, Math.abs(barrier.x() - visual.x()) + Math.abs(barrier.z() - visual.z()));
                assertEquals(visual.y(), barrier.y());
            }
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 2. ブロック生成
     * 検証契約: 各子部屋について、境界入口面とその両側に通路側接近面・部屋側着地点を生成する。
     */
    @Test
    void createsAnActiveRoomEntranceAtEveryChildBoundary() {
        DungeonDefinition definition = DungeonTestFixtures.definition();
        DungeonLayout layout = new DungeonLayoutPlanner().plan(definition, 112233L);
        DungeonBlockPlan plan = new DungeonBlockPlanner().plan(definition, layout);

        assertEquals(layout.rooms().size() - 1, plan.roomEntrancesByRoom().size());
        assertFalse(plan.roomEntrancesByRoom().containsKey(layout.startRoomId()));
        for (DungeonLayout.Connection connection : layout.connections()) {
            DungeonBlockPlan.RoomEntrance entrance = plan.roomEntrancesByRoom().get(connection.toRoomId());
            assertNotNull(entrance);
            assertFalse(entrance.gateBlocks().isEmpty());
            assertEquals(0, entrance.gateBlocks().size() % definition.generation().corridorHeight());
            assertFalse(entrance.corridorApproachBlocks().isEmpty());

            DungeonLayout.Room child = layout.rooms().stream()
                    .filter(room -> room.id() == connection.toRoomId())
                    .findFirst()
                    .orElseThrow();
            DungeonBlockPlan.Position destination = entrance.roomDestination();
            assertTrue(contains(child, destination.x(), destination.z()));
            assertTrue(entrance.gateBlocks().stream()
                    .filter(position -> position.y() == layout.baseY() + 1)
                    .anyMatch(position -> Math.abs(position.x() - destination.x())
                            + Math.abs(position.z() - destination.z()) == 1));
            assertTrue(entrance.corridorApproachBlocks().stream().allMatch(approach ->
                    entrance.gateBlocks().stream()
                            .filter(position -> position.y() == layout.baseY() + 1)
                            .anyMatch(position -> Math.abs(position.x() - approach.x())
                                    + Math.abs(position.z() - approach.z()) == 1)));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 2. ブロック生成
     * 検証契約: 指定再現seedを含む幅1/3/5/7、矩形/円形、直線/屈曲通路でも、incoming進行ゲート解放後のACTIVE入口は全開削境界を遮断し、他接続の閉鎖ゲートを含めない。
     */
    @Test
    void activeEntranceBlocksEveryActualOpeningForWideAndTurningCorridors() {
        boolean coveredStraight = false;
        boolean coveredTurn = false;
        for (DungeonRoomShape shape : List.of(DungeonRoomShape.RECTANGLE, DungeonRoomShape.CYLINDER)) {
            for (int width : List.of(1, 3, 5, 7)) {
                for (long seed : regressionSeeds(shape)) {
                    DungeonDefinition definition = definitionWithCorridor(width, shape);
                    DungeonLayout layout = new DungeonLayoutPlanner().plan(definition, seed);
                    DungeonBlockPlan plan = new DungeonBlockPlanner().plan(definition, layout);
                    int walkY = layout.baseY() + 1;
                    Map<Cell, DungeonBlockPlan.Placement> walkLayer = walkLayer(plan, walkY);
                    for (DungeonLayout.Connection connection : layout.connections()) {
                        boolean turns = hasTurnNearChildEntrance(connection.centerLine(), width);
                        coveredTurn |= turns;
                        coveredStraight |= !turns;
                        DungeonBlockPlan.RoomEntrance entrance = plan.roomEntrancesByRoom()
                                .get(connection.toRoomId());
                        Set<Cell> closedEntrance = new HashSet<>();
                        for (DungeonBlockPlan.Position position : entrance.gateBlocks()) {
                            if (position.y() == walkY) {
                                closedEntrance.add(new Cell(position.x(), position.z()));
                            }
                        }
                        assertTrue(java.util.Collections.disjoint(
                                        closedEntrance,
                                        progressionGateFootprintExcept(
                                                plan, walkY, connection.id())),
                                "ACTIVE glass must not replace another connection's closed gate");
                        DungeonBlockPlan.Position childSpawn = plan.spawnPointsByRoom()
                                .get(connection.toRoomId()).getFirst();
                        DungeonBlockPlan.Position parentSpawn = plan.spawnPointsByRoom()
                                .get(connection.fromRoomId()).getFirst();
                        Set<Cell> openedProgressionGate = progressionGateFootprint(
                                plan, walkY, connection.id());

                        boolean reachableAfterIncomingGateOpens = canReach(
                                walkLayer,
                                childSpawn,
                                parentSpawn,
                                openedProgressionGate,
                                Set.of());
                        assertTrue(reachableAfterIncomingGateOpens,
                                "AVAILABLE child must be reachable after its incoming gate opens: seed="
                                        + seed + ", width=" + width + ", shape=" + shape
                                        + ", connection=" + connection.id());
                        assertFalse(closedEntrance.isEmpty(),
                                "every reachable opening must have an ACTIVE cut set");
                        assertTrue(canReach(
                                        walkLayer,
                                        childSpawn,
                                        entrance.roomDestination(),
                                        Set.of(),
                                        Set.of()),
                                "room-side destination must be reachable from the child room spawn");

                        assertFalse(canReach(
                                        walkLayer,
                                        childSpawn,
                                        parentSpawn,
                                        openedProgressionGate,
                                        closedEntrance),
                                "ACTIVE entrance leaked: seed=" + seed
                                        + ", width=" + width
                                        + ", shape=" + shape
                                        + ", connection=" + connection.id());
                    }
                }
            }
        }
        assertTrue(coveredStraight, "regression matrix must include a straight connection");
        assertTrue(coveredTurn, "regression seed 2 must include a turning connection");
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 2. ブロック生成
     * 検証契約: 全ゲート閉鎖時は接続親子間を通れず、対象ゲートだけを開くと通行できる。
     */
    @Test
    void gatesBlockAndReleaseTheirParentChildRoutes() {
        DungeonDefinition definition = DungeonTestFixtures.definition();
        DungeonLayout layout = new DungeonLayoutPlanner().plan(definition, 112233L);
        DungeonBlockPlan plan = new DungeonBlockPlanner().plan(definition, layout);
        int walkY = layout.baseY() + 1;
        Map<Cell, DungeonBlockPlan.Placement> walkLayer = new HashMap<>();
        for (DungeonBlockPlan.Placement placement : plan.placements()) {
            if (placement.position().y() == walkY) {
                walkLayer.put(
                        new Cell(placement.position().x(), placement.position().z()),
                        placement
                );
            }
        }

        for (DungeonLayout.Connection connection : layout.connections()) {
            DungeonBlockPlan.Position from = plan.spawnPointsByRoom()
                    .get(connection.fromRoomId()).getFirst();
            DungeonBlockPlan.Position to = plan.spawnPointsByRoom()
                    .get(connection.toRoomId()).getFirst();
            assertFalse(canReach(walkLayer, from, to, Set.of()),
                    "closed gate must block connection " + connection.id());

            Set<Cell> openedGate = new HashSet<>();
            for (DungeonBlockPlan.Position position : plan.gateBlocksByConnection().get(connection.id())) {
                if (position.y() == walkY) {
                    openedGate.add(new Cell(position.x(), position.z()));
                }
            }
            for (DungeonBlockPlan.Position position : plan.gateBarrierBlocksByConnection().get(connection.id())) {
                if (position.y() == walkY) {
                    openedGate.add(new Cell(position.x(), position.z()));
                }
            }
            assertTrue(canReach(walkLayer, from, to, openedGate),
                    "opened gate must release connection " + connection.id());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 2. ブロック生成
     * 検証契約: chanceが1の中央柱には各部屋の床側と天井側へ東西南北の階段を合計八個生成する。
     */
    @Test
    void decoratesEveryGuaranteedPillarWithEightDirectionalStairs() {
        DungeonDefinition source = DungeonTestFixtures.definition();
        DungeonDefinition definition = new DungeonDefinition(
                source.schemaVersion(), source.id(), source.displayName(), source.recommendedLevel(),
                source.entry(), source.partySize(),
                source.generation(),
                new DungeonDefinition.Theme(
                        source.theme().floor(), source.theme().wall(), source.theme().ceiling(),
                        source.theme().corridor(), source.theme().gateMaterial(),
                        new DungeonDefinition.Pillar(
                                true, 1.0D, source.theme().pillar().material(),
                                source.theme().pillar().stairMaterial())
                ),
                source.encounter()
        );
        DungeonLayout layout = new DungeonLayoutPlanner().plan(definition, 445566L);
        DungeonBlockPlan plan = new DungeonBlockPlanner().plan(definition, layout);

        long stairCount = plan.placements().stream().filter(placement -> placement.stair() != null).count();

        assertEquals(layout.rooms().size() * 8L, stairCount);
        assertTrue(plan.placements().stream()
                .filter(placement -> placement.stair() != null)
                .allMatch(placement -> placement.material() == definition.theme().pillar().stairMaterial()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 2. ブロック生成
     * 検証契約: 各部屋と接続通路へ床置きのたいまつを生成し、Mob 出現候補と閉鎖ゲートを占有しない。
     */
    @Test
    void placesTorchesInRoomsAndCorridorsWithoutBlockingSpawnsOrGates() {
        DungeonDefinition definition = DungeonTestFixtures.definition();
        DungeonLayout layout = new DungeonLayoutPlanner().plan(definition, 778899L);
        DungeonBlockPlan plan = new DungeonBlockPlanner().plan(definition, layout);
        Set<DungeonBlockPlan.Position> torchPositions = plan.placements().stream()
                .filter(placement -> placement.material() == Material.TORCH)
                .map(DungeonBlockPlan.Placement::position)
                .collect(java.util.stream.Collectors.toSet());

        assertFalse(torchPositions.isEmpty());
        assertTrue(layout.rooms().stream().allMatch(room -> torchPositions.stream()
                .anyMatch(position -> room.bounds().contains(position.x(), position.z()))));
        assertTrue(layout.connections().stream().allMatch(connection -> connection.centerLine().stream()
                .map(point -> new DungeonBlockPlan.Position(point.x(), layout.baseY() + 1, point.z()))
                .anyMatch(torchPositions::contains)));
        assertTrue(torchPositions.stream().allMatch(position -> position.y() == layout.baseY() + 1));
        Set<DungeonBlockPlan.Position> spawnPositions = plan.spawnPointsByRoom().values().stream()
                .flatMap(List::stream)
                .collect(java.util.stream.Collectors.toSet());
        Set<DungeonBlockPlan.Position> gatePositions = plan.gateBlocksByConnection().values().stream()
                .flatMap(List::stream)
                .collect(java.util.stream.Collectors.toSet());
        plan.roomEntrancesByRoom().values().stream()
                .map(DungeonBlockPlan.RoomEntrance::gateBlocks)
                .forEach(gatePositions::addAll);
        assertTrue(torchPositions.stream().noneMatch(spawnPositions::contains));
        assertTrue(torchPositions.stream().noneMatch(gatePositions::contains));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 2. ブロック生成
     * 検証契約: 当たり判定のある床置き照明は、部屋と通路の床ブロックと同じ高さへ生成する。
     */
    @Test
    void placesCollidableLightsAtFloorHeightInRoomsAndCorridors() {
        for (Material lightMaterial : List.of(
                Material.LANTERN, Material.SEA_LANTERN, Material.JACK_O_LANTERN)) {
            DungeonDefinition definition = withLightMaterial(lightMaterial);
            DungeonLayout layout = new DungeonLayoutPlanner().plan(definition, 778899L);
            DungeonBlockPlan plan = new DungeonBlockPlanner().plan(definition, layout);
            Set<DungeonBlockPlan.Position> lightPositions = plan.placements().stream()
                    .filter(placement -> placement.material() == lightMaterial)
                    .map(DungeonBlockPlan.Placement::position)
                    .collect(java.util.stream.Collectors.toSet());

            assertTrue(lightMaterial.isCollidable(), lightMaterial + " must be collidable");
            assertFalse(lightPositions.isEmpty(), lightMaterial + " must be placed");
            assertTrue(lightPositions.stream().allMatch(position -> position.y() == layout.baseY()),
                    lightMaterial + " must be placed at floor height");
            assertTrue(layout.rooms().stream().allMatch(room -> lightPositions.stream()
                    .anyMatch(position -> room.bounds().contains(position.x(), position.z()))));
            assertTrue(layout.connections().stream().allMatch(connection -> connection.centerLine().stream()
                    .map(point -> new DungeonBlockPlan.Position(point.x(), layout.baseY(), point.z()))
                    .anyMatch(lightPositions::contains)));
            Set<Cell> lightCells = lightPositions.stream()
                    .map(position -> new Cell(position.x(), position.z()))
                    .collect(java.util.stream.Collectors.toSet());
            Set<Cell> spawnCells = plan.spawnPointsByRoom().values().stream()
                    .flatMap(List::stream)
                    .map(position -> new Cell(position.x(), position.z()))
                    .collect(java.util.stream.Collectors.toSet());
            assertTrue(lightCells.stream().noneMatch(spawnCells::contains),
                    lightMaterial + " must not overlap mob spawn candidates");
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 2. ブロック生成
     * 検証契約: 床埋め照明が唯一の Mob 出現候補を占有する生成結果は採用しない。
     */
    @Test
    void rejectsPlanWhenCollidableLightOccupiesOnlySpawnCandidate() {
        DungeonDefinition source = withLightMaterial(Material.SEA_LANTERN);
        DungeonDefinition.Theme theme = source.theme();
        DungeonDefinition definition = new DungeonDefinition(
                source.schemaVersion(), source.id(), source.displayName(), source.recommendedLevel(),
                source.entry(), source.partySize(), source.generation(),
                new DungeonDefinition.Theme(
                        theme.floor(), theme.wall(), theme.ceiling(), theme.corridor(),
                        theme.gateMaterial(),
                        new DungeonDefinition.Pillar(
                                false, theme.pillar().chance(), theme.pillar().material(),
                                theme.pillar().stairMaterial()),
                        theme.lightMaterial(), theme.decorations()),
                source.encounter());
        DungeonLayout layout = new DungeonLayout(
                445566L, 5, 5, definition.generation().baseY(), definition.generation().roomHeight(),
                List.of(new DungeonLayout.Room(
                        0, new DungeonLayout.Rect(0, 0, 4, 4),
                        DungeonRoomShape.RECTANGLE, DungeonLayout.RoomRole.START, 0
                )),
                List.of(), 0, 0
        );

        assertThrows(IllegalStateException.class,
                () -> new DungeonBlockPlanner().plan(definition, layout));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 2. ブロック生成
     * 検証契約: 各部屋へ床置きの TORCH を配置し、中央柱と重なる候補では歩行可能な別の床へ配置する。
     */
    @Test
    void placesTorchInMinimumRoomWithGuaranteedCentralPillar() {
        DungeonDefinition source = DungeonTestFixtures.definition();
        DungeonDefinition definition = new DungeonDefinition(
                source.schemaVersion(), source.id(), source.displayName(), source.recommendedLevel(),
                source.entry(), source.partySize(),
                source.generation(),
                new DungeonDefinition.Theme(
                        source.theme().floor(), source.theme().wall(), source.theme().ceiling(),
                        source.theme().corridor(), source.theme().gateMaterial(),
                        new DungeonDefinition.Pillar(
                                true, 1.0D, source.theme().pillar().material(),
                                source.theme().pillar().stairMaterial())
                ),
                source.encounter()
        );
        DungeonLayout layout = new DungeonLayout(
                445566L, 7, 7, definition.generation().baseY(), definition.generation().roomHeight(),
                List.of(new DungeonLayout.Room(
                        0, new DungeonLayout.Rect(0, 0, 6, 6),
                        DungeonRoomShape.RECTANGLE, DungeonLayout.RoomRole.START, 0
                )),
                List.of(), 0, 0
        );

        DungeonBlockPlan plan = new DungeonBlockPlanner().plan(definition, layout);
        Set<DungeonBlockPlan.Position> torchPositions = plan.placements().stream()
                .filter(placement -> placement.material() == Material.TORCH)
                .map(DungeonBlockPlan.Placement::position)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(1, torchPositions.size());
        assertFalse(torchPositions.contains(new DungeonBlockPlan.Position(
                3, definition.generation().baseY() + 1, 3)));
    }

    /**
     * テスト用ダンジョン定義の照明 Material だけを差し替えます。
     *
     * @param lightMaterial 差し替える床置き照明 Material
     * @return 指定した照明 Material を持つテスト用ダンジョン定義
     */
    private DungeonDefinition withLightMaterial(Material lightMaterial) {
        DungeonDefinition source = DungeonTestFixtures.definition();
        DungeonDefinition.Theme theme = source.theme();
        return new DungeonDefinition(
                source.schemaVersion(), source.id(), source.displayName(), source.recommendedLevel(),
                source.entry(), source.partySize(), source.generation(),
                new DungeonDefinition.Theme(
                        theme.floor(), theme.wall(), theme.ceiling(), theme.corridor(),
                        theme.gateMaterial(), theme.pillar(), lightMaterial, theme.decorations()),
                source.encounter());
    }

    private boolean canReach(
            Map<Cell, DungeonBlockPlan.Placement> walkLayer,
            DungeonBlockPlan.Position from,
            DungeonBlockPlan.Position to,
            Set<Cell> openedGate
    ) {
        return canReach(walkLayer, from, to, openedGate, Set.of());
    }

    private boolean canReach(
            Map<Cell, DungeonBlockPlan.Placement> walkLayer,
            DungeonBlockPlan.Position from,
            DungeonBlockPlan.Position to,
            Set<Cell> openedGate,
            Set<Cell> blocked
    ) {
        Set<Cell> passable = new HashSet<>();
        for (Map.Entry<Cell, DungeonBlockPlan.Placement> entry : walkLayer.entrySet()) {
            DungeonBlockPlan.Placement placement = entry.getValue();
            if (!blocked.contains(entry.getKey())
                    && (placement.material().isAir() || !placement.material().isSolid()
                    || placement.stair() != null
                    || openedGate.contains(entry.getKey()))) {
                passable.add(entry.getKey());
            }
        }

        Cell start = new Cell(from.x(), from.z());
        Cell goal = new Cell(to.x(), to.z());
        if (!passable.contains(start) || !passable.contains(goal)) {
            return false;
        }
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        Set<Cell> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            Cell current = queue.removeFirst();
            if (current.equals(goal)) {
                return true;
            }
            for (Cell next : List.of(
                    new Cell(current.x() + 1, current.z()),
                    new Cell(current.x() - 1, current.z()),
                    new Cell(current.x(), current.z() + 1),
                    new Cell(current.x(), current.z() - 1)
            )) {
                if (passable.contains(next) && visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return false;
    }

    private Map<Cell, DungeonBlockPlan.Placement> walkLayer(DungeonBlockPlan plan, int walkY) {
        Map<Cell, DungeonBlockPlan.Placement> walkLayer = new HashMap<>();
        for (DungeonBlockPlan.Placement placement : plan.placements()) {
            if (placement.position().y() == walkY) {
                walkLayer.put(new Cell(placement.position().x(), placement.position().z()), placement);
            }
        }
        return walkLayer;
    }

    private Set<Cell> progressionGateFootprint(
            DungeonBlockPlan plan,
            int walkY,
            int connectionId
    ) {
        Set<Cell> opened = new HashSet<>();
        plan.gateBlocksByConnection().get(connectionId).stream()
                .filter(position -> position.y() == walkY)
                .map(position -> new Cell(position.x(), position.z()))
                .forEach(opened::add);
        plan.gateBarrierBlocksByConnection().get(connectionId).stream()
                .filter(position -> position.y() == walkY)
                .map(position -> new Cell(position.x(), position.z()))
                .forEach(opened::add);
        return opened;
    }

    private Set<Cell> progressionGateFootprintExcept(
            DungeonBlockPlan plan,
            int walkY,
            int excludedConnectionId
    ) {
        Set<Cell> protectedGate = new HashSet<>();
        plan.gateBlocksByConnection().forEach((connectionId, positions) -> {
            if (connectionId != excludedConnectionId) {
                positions.stream()
                        .filter(position -> position.y() == walkY)
                        .map(position -> new Cell(position.x(), position.z()))
                        .forEach(protectedGate::add);
            }
        });
        plan.gateBarrierBlocksByConnection().forEach((connectionId, positions) -> {
            if (connectionId != excludedConnectionId) {
                positions.stream()
                        .filter(position -> position.y() == walkY)
                        .map(position -> new Cell(position.x(), position.z()))
                        .forEach(protectedGate::add);
            }
        });
        return protectedGate;
    }

    private List<Long> regressionSeeds(DungeonRoomShape shape) {
        if (shape == DungeonRoomShape.RECTANGLE) {
            return List.of(2L, 4L, 112233L);
        }
        return List.of(2L, 54L, 68L, 74L, 95L, 112233L);
    }

    private boolean hasTurnNearChildEntrance(List<DungeonLayout.Point> line, int corridorWidth) {
        int firstIndex = Math.max(2, line.size() - corridorWidth - 2);
        for (int index = firstIndex; index < line.size(); index++) {
            DungeonLayout.Point first = line.get(index - 2);
            DungeonLayout.Point middle = line.get(index - 1);
            DungeonLayout.Point last = line.get(index);
            int firstDx = Integer.compare(middle.x(), first.x());
            int firstDz = Integer.compare(middle.z(), first.z());
            int nextDx = Integer.compare(last.x(), middle.x());
            int nextDz = Integer.compare(last.z(), middle.z());
            if (firstDx != nextDx || firstDz != nextDz) {
                return true;
            }
        }
        return false;
    }

    private boolean contains(DungeonLayout.Room room, int x, int z) {
        if (!room.bounds().contains(x, z)) {
            return false;
        }
        if (room.shape() == DungeonRoomShape.RECTANGLE) {
            return true;
        }
        double radiusX = Math.max(1.0D, room.bounds().width() / 2.0D);
        double radiusZ = Math.max(1.0D, room.bounds().depth() / 2.0D);
        double normalizedX = (x - room.bounds().centerX()) / radiusX;
        double normalizedZ = (z - room.bounds().centerZ()) / radiusZ;
        return normalizedX * normalizedX + normalizedZ * normalizedZ <= 1.0D;
    }

    private DungeonDefinition definitionWithCorridor(int width, DungeonRoomShape shape) {
        DungeonDefinition source = DungeonTestFixtures.definition();
        DungeonDefinition.Generation generation = source.generation();
        return new DungeonDefinition(
                source.schemaVersion(), source.id(), source.displayName(), source.recommendedLevel(),
                source.entry(), source.partySize(),
                new DungeonDefinition.Generation(
                        generation.areaWidth(), generation.areaDepth(), generation.baseY(),
                        generation.roomCount(), generation.roomSize(), generation.roomHeight(),
                        width, generation.corridorHeight(), generation.splitRatioMin(),
                        generation.splitRatioMax(),
                        List.of(new DungeonDefinition.WeightedShape(shape, 1)),
                        generation.roomTypes()),
                source.theme(), source.encounter()
        );
    }

    private record Cell(int x, int z) {
    }
}
