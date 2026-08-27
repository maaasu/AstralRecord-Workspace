package io.github.maaasu.astralRecord.feature.dungeon.generation;

import io.github.maaasu.astralRecord.feature.dungeon.DungeonTestFixtures;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomType;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonLayoutPlannerTest {
    private final DungeonLayoutPlanner planner = new DungeonLayoutPlanner();

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 1. BSP 配置生成
     * 検証契約: 同じDungeonDefinitionとseedは部屋、接続、開始・ボス役割を含む同じDungeonLayoutを返す。
     */
    @Test
    void reproducesExactlyTheSameLayoutForTheSameSeed() {
        DungeonDefinition definition = DungeonTestFixtures.definition();

        DungeonLayout first = planner.plan(definition, 987654321L);
        DungeonLayout second = planner.plan(definition, 987654321L);

        assertEquals(first, second);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 1. BSP 配置生成
     * 検証契約: seedごとの部屋数はroomCount範囲内で変化し、葉区画へ置いた部屋同士は重ならない。
     */
    @Test
    void choosesVariableRoomCountWithinRangeWithoutRoomOverlap() {
        DungeonDefinition definition = DungeonTestFixtures.definition();
        Set<Integer> observedCounts = new HashSet<>();

        for (long seed = 0; seed < 50; seed++) {
            DungeonLayout layout = planner.plan(definition, seed);
            observedCounts.add(layout.rooms().size());
            assertTrue(layout.rooms().size() >= definition.generation().roomCount().min());
            assertTrue(layout.rooms().size() <= definition.generation().roomCount().max());
            for (int first = 0; first < layout.rooms().size(); first++) {
                for (int second = first + 1; second < layout.rooms().size(); second++) {
                    assertFalse(layout.rooms().get(first).bounds()
                            .intersects(layout.rooms().get(second).bounds()));
                }
            }
        }

        assertTrue(observedCounts.size() > 1);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 1. BSP 配置生成
     * 検証契約: 接続は全室へ到達できる部屋数マイナス一辺の木を構成する。
     */
    @Test
    void createsAConnectedTreeAcrossAllRooms() {
        DungeonLayout layout = planner.plan(DungeonTestFixtures.definition(), 12345L);
        Map<Integer, Set<Integer>> adjacency = adjacency(layout);

        Set<Integer> reached = new LinkedHashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(layout.startRoomId());
        reached.add(layout.startRoomId());
        while (!queue.isEmpty()) {
            for (int next : adjacency.get(queue.removeFirst())) {
                if (reached.add(next)) {
                    queue.addLast(next);
                }
            }
        }

        assertEquals(layout.rooms().size() - 1, layout.connections().size());
        assertEquals(layout.rooms().size(), reached.size());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_1-モデル定義.md
     * 章・見出し: # 32_1-モデル定義 > ## 2. DungeonLayout
     * 検証契約: STARTは部屋矩形のminX、minZ、IDの昇順で一意に決定する。
     */
    @Test
    void selectsTheStartRoomByMinimumBoundsCoordinates() {
        DungeonDefinition definition = DungeonTestFixtures.definition();

        for (long seed = 0; seed < 50; seed++) {
            DungeonLayout layout = planner.plan(definition, seed);
            int expectedStartRoomId = layout.rooms().stream()
                    .min(Comparator
                            .comparingInt((DungeonLayout.Room room) -> room.bounds().minX())
                            .thenComparingInt(room -> room.bounds().minZ())
                            .thenComparingInt(DungeonLayout.Room::id))
                    .orElseThrow()
                    .id();

            assertEquals(expectedStartRoomId, layout.startRoomId());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 1. BSP 配置生成
     * 検証契約: 各通路中心線は接続元・接続先以外の部屋を通過せず、部屋外で別通路の中心線と交差しない。
     */
    @Test
    void routesCorridorsWithoutCrossingUnrelatedRoomsOrCorridors() {
        for (long seed = 0; seed < 20; seed++) {
            DungeonLayout layout = planner.plan(DungeonTestFixtures.definition(), seed);
            for (DungeonLayout.Connection connection : layout.connections()) {
                for (DungeonLayout.Point point : connection.centerLine()) {
                    for (DungeonLayout.Room room : layout.rooms()) {
                        if (room.id() != connection.fromRoomId() && room.id() != connection.toRoomId()) {
                            assertFalse(room.bounds().contains(point.x(), point.z()));
                        }
                    }
                }
            }
            for (int first = 0; first < layout.connections().size(); first++) {
                DungeonLayout.Connection left = layout.connections().get(first);
                Set<DungeonLayout.Point> leftPoints = new HashSet<>(left.centerLine());
                for (int second = first + 1; second < layout.connections().size(); second++) {
                    DungeonLayout.Connection right = layout.connections().get(second);
                    Set<Integer> sharedRooms = new HashSet<>(Set.of(left.fromRoomId(), left.toRoomId()));
                    sharedRooms.retainAll(Set.of(right.fromRoomId(), right.toRoomId()));
                    for (DungeonLayout.Point point : right.centerLine()) {
                        if (!leftPoints.contains(point)) {
                            continue;
                        }
                        boolean insideSharedRoom = sharedRooms.stream()
                                .map(roomId -> layout.rooms().get(roomId))
                                .anyMatch(room -> room.bounds().contains(point.x(), point.z()));
                        assertTrue(insideSharedRoom);
                    }
                }
            }
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 1. BSP 配置生成
     * 検証契約: BOSS役割は一室だけに付き、その部屋は開始部屋以外でグラフ距離が最大の葉である。
     */
    @Test
    void assignsTheOnlyBossRoleToAFarthestLeaf() {
        DungeonLayout layout = planner.plan(DungeonTestFixtures.definition(), 24680L);
        Map<Integer, Set<Integer>> adjacency = adjacency(layout);
        DungeonLayout.Room boss = layout.rooms().stream()
                .filter(room -> room.role() == DungeonLayout.RoomRole.BOSS)
                .findFirst()
                .orElseThrow();

        long bossCount = layout.rooms().stream()
                .filter(room -> room.role() == DungeonLayout.RoomRole.BOSS)
                .count();
        int farthestLeafDistance = layout.rooms().stream()
                .filter(room -> room.id() != layout.startRoomId())
                .filter(room -> adjacency.get(room.id()).size() == 1)
                .mapToInt(DungeonLayout.Room::distanceFromStart)
                .max()
                .orElseThrow();

        assertEquals(1L, bossCount);
        assertEquals(1, adjacency.get(boss.id()).size());
        assertEquals(farthestLeafDistance, boss.distanceFromStart());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 1. BSP 配置生成
     * 検証契約: roomTypesの正の相対weightを用い、同じdefinitionとseedでは各roomへ同じtypeを決定的に割り当てる。
     */
    @Test
    void assignsWeightedRoomTypesDeterministically() {
        DungeonDefinition source = DungeonTestFixtures.definition();
        DungeonDefinition.Generation generation = source.generation();
        DungeonDefinition definition = new DungeonDefinition(
                source.schemaVersion(), source.id(), source.displayName(), source.recommendedLevel(),
                source.entry(), source.partySize(),
                source.challenge(),
                new DungeonDefinition.Generation(
                        generation.areaWidth(), generation.areaDepth(), generation.baseY(),
                        generation.roomCount(), generation.roomSize(), generation.roomHeight(),
                        generation.corridorWidth(), generation.corridorHeight(),
                        generation.splitRatioMin(), generation.splitRatioMax(), generation.roomShapes(),
                        List.of(
                                new DungeonDefinition.WeightedRoomType(DungeonRoomType.STANDARD, 1),
                                new DungeonDefinition.WeightedRoomType(DungeonRoomType.SUPPORT_HALL, 1),
                                new DungeonDefinition.WeightedRoomType(DungeonRoomType.COLLAPSED, 1),
                                new DungeonDefinition.WeightedRoomType(DungeonRoomType.ORE_CHAMBER, 1)
                        )
                ),
                source.theme(), source.encounter(), source.clearRewards()
        );
        Set<DungeonRoomType> observed = new HashSet<>();

        for (long seed = 0; seed < 100; seed++) {
            DungeonLayout first = planner.plan(definition, seed);
            DungeonLayout second = planner.plan(definition, seed);
            assertEquals(
                    first.rooms().stream().map(DungeonLayout.Room::type).toList(),
                    second.rooms().stream().map(DungeonLayout.Room::type).toList());
            first.rooms().stream().map(DungeonLayout.Room::type).forEach(observed::add);
        }

        assertEquals(Set.of(DungeonRoomType.values()), observed);
    }

    private Map<Integer, Set<Integer>> adjacency(DungeonLayout layout) {
        Map<Integer, Set<Integer>> result = new HashMap<>();
        for (DungeonLayout.Room room : layout.rooms()) {
            result.put(room.id(), new LinkedHashSet<>());
        }
        for (DungeonLayout.Connection connection : layout.connections()) {
            result.get(connection.fromRoomId()).add(connection.toRoomId());
            result.get(connection.toRoomId()).add(connection.fromRoomId());
        }
        return result;
    }
}
