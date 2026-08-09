package io.github.maaasu.astralRecord.feature.dungeon.generation;

import io.github.maaasu.astralRecord.feature.dungeon.DungeonTestFixtures;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonBlockPlan;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
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
        int expectedGateBlocks = definition.generation().corridorWidth()
                * definition.generation().corridorHeight();
        for (DungeonLayout.Connection connection : layout.connections()) {
            List<DungeonBlockPlan.Position> gate = plan.gateBlocksByConnection().get(connection.id());
            assertNotNull(gate);
            assertEquals(expectedGateBlocks, gate.size());
            for (DungeonBlockPlan.Position position : gate) {
                Material finalMaterial = plan.placements().stream()
                        .filter(placement -> placement.position().equals(position))
                        .findFirst()
                        .orElseThrow()
                        .material();
                assertEquals(definition.theme().gateMaterial(), finalMaterial);
            }
        }
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
                source.schemaVersion(), source.id(), source.displayName(), source.worldId(), source.partySize(),
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

    private boolean canReach(
            Map<Cell, DungeonBlockPlan.Placement> walkLayer,
            DungeonBlockPlan.Position from,
            DungeonBlockPlan.Position to,
            Set<Cell> openedGate
    ) {
        Set<Cell> passable = new HashSet<>();
        for (Map.Entry<Cell, DungeonBlockPlan.Placement> entry : walkLayer.entrySet()) {
            DungeonBlockPlan.Placement placement = entry.getValue();
            if (placement.material().isAir() || placement.stair() != null
                    || openedGate.contains(entry.getKey())) {
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

    private record Cell(int x, int z) {
    }
}
