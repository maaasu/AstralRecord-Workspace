package io.github.maaasu.astralRecord.feature.dungeon.repository;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonDefinitionRepositoryTest {
    @TempDir
    Path tempDirectory;

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_1-モデル定義.md
     * 章・見出し: # 32_1-モデル定義 > ## 1. DungeonDefinition
     * 検証契約: 一つのYAMLから参加範囲、生成範囲、形状・Material重み、遭遇Mobを一つのDungeonDefinitionへ読み込む。
     */
    @Test
    void parsesTheSingleFileDungeonSchema() throws IOException {
        Path directory = tempDirectory.resolve("65.features.dungeon");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("v1.test.yml"), yaml());

        List<DungeonDefinition> definitions = FileDatabaseManager.getInstance().withReloadSnapshot(
                new FileDatabaseManager.ReloadSnapshot(tempDirectory.toFile()),
                () -> new DungeonDefinitionRepository().findAll()
        );

        assertEquals(1, definitions.size());
        DungeonDefinition definition = definitions.getFirst();
        assertEquals("test_dungeon", definition.id());
        assertEquals("test_world", definition.worldId());
        assertEquals(new DungeonDefinition.IntRange(7, 11), definition.generation().roomCount());
        assertEquals(DungeonRoomShape.CYLINDER, definition.generation().roomShapes().get(1).shape());
        assertEquals(Material.MOSSY_STONE_BRICKS, definition.theme().floor().get(1).material());
        assertTrue(definition.theme().pillar().enabled());
        assertEquals("boss", definition.encounter().bossMobId());
    }

    private String yaml() {
        return """
                schemaVersion: 1
                id: test_dungeon
                displayName: Test Dungeon
                worldRef: world:test_world
                party:
                  min: 1
                  max: 4
                generation:
                  area:
                    width: 128
                    depth: 128
                  baseY: 64
                  roomCount:
                    min: 7
                    max: 11
                  roomSize:
                    min: 11
                    max: 23
                  roomHeight: 8
                  corridorWidth: 3
                  corridorHeight: 4
                  splitRatio:
                    min: 0.35
                    max: 0.50
                  roomShapes:
                    - type: RECTANGLE
                      weight: 70
                    - type: CYLINDER
                      weight: 30
                theme:
                  floor:
                    - material: STONE_BRICKS
                      weight: 60
                    - material: MOSSY_STONE_BRICKS
                      weight: 40
                  wall:
                    - material: STONE_BRICKS
                      weight: 1
                  ceiling:
                    - material: STONE_BRICKS
                      weight: 1
                  corridor:
                    - material: COBBLESTONE
                      weight: 1
                  gateMaterial: IRON_BARS
                  pillar:
                    enabled: true
                    chance: 0.35
                    material: CHISELED_STONE_BRICKS
                    stairMaterial: STONE_BRICK_STAIRS
                encounter:
                  normalMobPool:
                    - mobId: mob:weak
                      weight: 70
                    - mobId: mob:strong
                      weight: 30
                  mobsPerRoom:
                    min: 2
                    max: 5
                  firstCombatRoomMaxMobLevel: 10
                  bossMobId: mob:boss
                """;
    }
}
