package io.github.maaasu.astralRecord.feature.dungeon.generation;

import io.github.maaasu.astralRecord.feature.dungeon.DungeonTestFixtures;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonBlockPlan;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomType;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRoomTypeDecorationTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 2. ブロック生成
     * 検証契約: room type装飾はseed決定的で、support/beam・rubble・accentを使いつつ中央導線とspawn候補を塞がない。
     */
    @Test
    void decoratesRoomTypesDeterministicallyWithoutBlockingRoutesOrSpawns() {
        DungeonDefinition source = DungeonTestFixtures.definition();
        DungeonDefinition.Theme theme = source.theme();
        DungeonDefinition definition = new DungeonDefinition(
                source.schemaVersion(), source.id(), source.displayName(), source.entry(), source.partySize(),
                source.challenge(), source.generation(),
                new DungeonDefinition.Theme(
                        theme.floor(), theme.wall(), theme.ceiling(), theme.corridor(),
                        theme.gateMaterial(),
                        new DungeonDefinition.Pillar(false, 0.0D,
                                theme.pillar().material(), theme.pillar().stairMaterial()),
                        Material.SOUL_TORCH,
                        new DungeonDefinition.Decorations(
                                Material.SPRUCE_LOG,
                                Material.STRIPPED_SPRUCE_LOG,
                                List.of(new DungeonDefinition.WeightedMaterial(Material.COBBLED_DEEPSLATE, 1)),
                                List.of(new DungeonDefinition.WeightedMaterial(Material.DEEPSLATE_IRON_ORE, 1))
                        )
                ),
                source.encounter(), source.clearRewards()
        );
        DungeonLayout layout = new DungeonLayout(
                424242L,
                128,
                128,
                source.generation().baseY(),
                source.generation().roomHeight(),
                List.of(
                        room(0, 0, DungeonRoomType.STANDARD, DungeonLayout.RoomRole.START),
                        room(1, 30, DungeonRoomType.SUPPORT_HALL, DungeonLayout.RoomRole.NORMAL),
                        room(2, 60, DungeonRoomType.COLLAPSED, DungeonLayout.RoomRole.NORMAL),
                        room(3, 90, DungeonRoomType.ORE_CHAMBER, DungeonLayout.RoomRole.BOSS)
                ),
                List.of(),
                0,
                3
        );
        DungeonBlockPlanner planner = new DungeonBlockPlanner();

        DungeonBlockPlan first = planner.plan(definition, layout);
        DungeonBlockPlan second = planner.plan(definition, layout);

        assertEquals(first, second);
        assertTrue(first.placements().stream().anyMatch(placement ->
                placement.material() == Material.SPRUCE_LOG));
        assertTrue(first.placements().stream().anyMatch(placement ->
                placement.material() == Material.STRIPPED_SPRUCE_LOG));
        assertTrue(first.placements().stream().anyMatch(placement ->
                placement.material() == Material.COBBLED_DEEPSLATE));
        assertTrue(first.placements().stream().anyMatch(placement ->
                placement.material() == Material.DEEPSLATE_IRON_ORE));

        DungeonLayout.Room collapsed = layout.rooms().get(2);
        int walkY = layout.baseY() + 1;
        assertFalse(first.placements().stream().anyMatch(placement ->
                placement.position().y() == walkY
                        && placement.material() == Material.COBBLED_DEEPSLATE
                        && (placement.position().x() == collapsed.bounds().centerX()
                        || placement.position().z() == collapsed.bounds().centerZ())));

        Map<DungeonBlockPlan.Position, Material> materials = new LinkedHashMap<>();
        first.placements().forEach(placement -> materials.put(placement.position(), placement.material()));
        first.spawnPointsByRoom().values().stream().flatMap(List::stream).forEach(position ->
                assertEquals(Material.AIR, materials.get(position)));
    }

    private DungeonLayout.Room room(
            int id,
            int minX,
            DungeonRoomType type,
            DungeonLayout.RoomRole role
    ) {
        return new DungeonLayout.Room(
                id,
                new DungeonLayout.Rect(minX, 0, minX + 20, 20),
                DungeonRoomShape.RECTANGLE,
                type,
                role,
                id
        );
    }
}
