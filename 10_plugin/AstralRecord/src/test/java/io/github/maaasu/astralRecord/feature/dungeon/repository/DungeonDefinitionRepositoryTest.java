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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertEquals("test_world", definition.entry().worldId());
        assertEquals(2.0D, definition.entry().radius());
        assertEquals(new DungeonDefinition.IntRange(7, 11), definition.generation().roomCount());
        assertEquals(DungeonRoomShape.CYLINDER, definition.generation().roomShapes().get(1).shape());
        assertEquals(Material.STONE_BRICKS, definition.theme().floor().getFirst().material());
        assertTrue(!definition.theme().pillar().enabled());
        assertEquals("boss", definition.encounter().bossMobId());
        assertEquals(0, definition.challenge().deathLimit());
        assertEquals(7L, definition.challenge().reviveDelaySeconds());
        assertEquals("rare_fragment", definition.clearRewards().items().getFirst().itemId());
        assertEquals(5.0D, definition.clearRewards().items().getFirst().rate());
        assertEquals("1~2", definition.clearRewards().items().getFirst().amount());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_1-モデル定義.md
     * 章・見出し: # 32_1-モデル定義 > ## 1. DungeonDefinition
     * 検証契約: 本番向け最小構成マスタが追加のWorldマスタなしでDungeonDefinitionへ読み込める。
     */
    @Test
    void parsesProductionMinimalDungeonMaster() {
        Path repositoryRoot = findRepositoryRoot();
        Path filebase = repositoryRoot.resolve("40_filebase");

        List<DungeonDefinition> definitions = FileDatabaseManager.getInstance().withReloadSnapshot(
                new FileDatabaseManager.ReloadSnapshot(filebase.toFile()),
                () -> new DungeonDefinitionRepository().findAll()
        );

        assertEquals(1, definitions.size());
        assertEquals("twilight_mine", definitions.getFirst().id());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_1-モデル定義.md
     * 章・見出し: # 32_1-モデル定義 > ## 1. DungeonDefinition
     * 検証契約: challengeとclear rewardの省略項目にだけ既定値を適用する。
     */
    @Test
    void appliesChallengeAndRewardDefaultsOnlyWhenKeysAreOmitted() throws IOException {
        String source = yaml()
                .replace("  deathLimit: 0\n", "")
                .replace("  reviveDelaySeconds: 7\n", "")
                .replace("      rate: 5.0\n", "")
                .replace("      amount: \"1~2\"\n", "");

        DungeonDefinition definition = load(source);

        assertEquals(5, definition.challenge().deathLimit());
        assertEquals(5L, definition.challenge().reviveDelaySeconds());
        assertEquals(100.0D, definition.clearRewards().items().getFirst().rate());
        assertEquals("1", definition.clearRewards().items().getFirst().amount());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_1-モデル定義.md
     * 章・見出し: # 32_1-モデル定義 > ## 1. DungeonDefinition
     * 検証契約: 明示したchallenge整数とclear reward rateが数値型でなければloadを拒否する。
     */
    @Test
    void rejectsExplicitNonNumericChallengeAndRewardValues() {
        assertThrows(IllegalArgumentException.class, () -> load(
                yaml().replace("deathLimit: 0", "deathLimit: invalid")));
        assertThrows(IllegalArgumentException.class, () -> load(
                yaml().replace("rate: 5.0", "rate: invalid")));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_1-モデル定義.md
     * 章・見出し: # 32_1-モデル定義 > ## 1. DungeonDefinition
     * 検証契約: clear reward amountは正整数または正しい昇順range以外をload時に拒否する。
     */
    @Test
    void rejectsInvalidRewardAmount() {
        for (String amount : List.of("-1", "0", "\"3~1\"", "\"invalid\"", "1.5", "\"1~\"")) {
            assertThrows(IllegalArgumentException.class, () -> load(
                    yaml().replace("amount: \"1~2\"", "amount: " + amount)));
        }
    }

    private DungeonDefinition load(String source) throws IOException {
        Path directory = tempDirectory.resolve("65.features.dungeon");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("v1.test.yml"), source);
        return FileDatabaseManager.getInstance().withReloadSnapshot(
                new FileDatabaseManager.ReloadSnapshot(tempDirectory.toFile()),
                () -> new DungeonDefinitionRepository().findAll()
        ).getFirst();
    }

    private Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve("40_filebase"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Repository root with 40_filebase was not found");
        }
        return current;
    }

    private String yaml() {
        return """
                schemaVersion: 1
                id: test_dungeon
                displayName: Test Dungeon
                entry:
                  worldRef: world:test_world
                  x: 1.5
                  y: 64.0
                  z: 2.5
                challenge:
                  deathLimit: 0
                  reviveDelaySeconds: 7
                encounter:
                  normalMobPool:
                    - mobId: mob:weak
                    - mobId: mob:strong
                  bossMobId: mob:boss
                clearRewards:
                  items:
                    - itemId: item:rare_fragment
                      rate: 5.0
                      amount: "1~2"
                """;
    }
}
