package io.github.maaasu.astralRecord.infrastructure.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalMasterOverlayTest {
    @TempDir Path temporaryPluginData;

    private static final String MOB = "40.features.mob/enemy/v1.test_enemy.yml";
    private static final String SPAWNER = "41.features.mob.spawner/v1.test_spawner.yml";
    private static final String MOB_YAML = """
            schemaVersion: 1
            id: test_enemy
            type: MOB
            category: ENEMY
            level: 15
            entityType: PIG
            levels:
              - level: 16
                name: Test
            """;
    private static final String SPAWNER_YAML = """
            schemaVersion: 1
            id: test_spawner
            type: MOB_SPAWNER
            spawnMobs:
              - mobId: mob:test_enemy
                level: 16
                weight: 100
            """;

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## 共通基盤の設定スナップショットと入力正規化 > ### Dev ローカルマスタオーバーレイ
     * 検証契約: 明示的に無効な場合、ローカルフォルダがなくても API マスタ経路への影響を作らない。
     */
    @Test
    void disabledOverlayNeedsNoFiles() {
        LocalMasterOverlay overlay = new LocalMasterOverlay(temporaryPluginData, false);
        assertTrue(overlay.loadSnapshot().mobs().isEmpty());
        assertTrue(overlay.currentSnapshot().spawners().isEmpty());
        LocalMasterOverlay unsafe = new LocalMasterOverlay(temporaryPluginData, () -> true, () -> false);
        assertThrows(IllegalStateException.class, unsafe::loadSnapshot);
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## 共通基盤の設定スナップショットと入力正規化 > ### Dev ローカルマスタオーバーレイ
     * 検証契約: manifest に列挙した原稿だけを SHA-256 検証して読み、同じ ID はローカル原稿を優先する。
     */
    @Test
    void declaredFilesLoadAndLocalIdWins() throws Exception {
        writeFixture(MOB_YAML, SPAWNER_YAML);
        Files.writeString(temporaryPluginData.resolve("local-master-overlay/ignored.yml"), "id: ignored\n");
        LocalMasterOverlay overlay = new LocalMasterOverlay(temporaryPluginData, true);
        LocalMasterOverlay.Snapshot snapshot = overlay.loadSnapshot();
        assertEquals(List.of("test_enemy"), List.copyOf(snapshot.mobs().keySet()));
        assertEquals(List.of("test_spawner"), List.copyOf(snapshot.spawners().keySet()));
        assertEquals(List.of("local-a", "remote-b"), LocalMasterOverlay.mergeById(
                List.of("remote-a", "remote-b"), Map.of("a", "local-a"),
                value -> value.substring(value.length() - 1)));
        overlay.replaceSnapshot(snapshot);
        assertSame(snapshot, overlay.currentSnapshot());
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## 共通基盤の設定スナップショットと入力正規化 > ### Dev ローカルマスタオーバーレイ
     * 検証契約: 再読込時の改変・不正パス・不正 YAML を拒否し、公開済み snapshot を保持する。
     */
    @Test
    void invalidManifestOrContentKeepsPublishedSnapshot() throws Exception {
        writeFixture(MOB_YAML, SPAWNER_YAML);
        LocalMasterOverlay overlay = new LocalMasterOverlay(temporaryPluginData, true);
        LocalMasterOverlay.Snapshot published = overlay.loadSnapshot();
        overlay.replaceSnapshot(published);
        Files.writeString(temporaryPluginData.resolve("local-master-overlay").resolve(MOB), MOB_YAML + "name: changed\n");
        assertThrows(IllegalStateException.class, overlay::loadSnapshot);
        assertSame(published, overlay.currentSnapshot());

        writeFixture(MOB_YAML, SPAWNER_YAML);
        Path manifest = temporaryPluginData.resolve("local-master-overlay/manifest.yml");
        Files.writeString(manifest, """
                schemaVersion: 1
                files:
                  - path: ../config.yml
                    sha256: %s
                """.formatted("0".repeat(64)));
        assertThrows(IllegalStateException.class, overlay::loadSnapshot);
        assertSame(published, overlay.currentSnapshot());

        Files.writeString(manifest, """
                schemaVersion: 1
                files:
                  - ignored-scalar
                """);
        assertThrows(IllegalStateException.class, overlay::loadSnapshot);
        assertSame(published, overlay.currentSnapshot());

        writeFixture(MOB_YAML.replace("level: 15", "level: zero"), SPAWNER_YAML);
        assertThrows(IllegalStateException.class, overlay::loadSnapshot);
        assertSame(published, overlay.currentSnapshot());
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## 共通基盤の設定スナップショットと入力正規化 > ### Dev ローカルマスタオーバーレイ
     * 検証契約: ローカル Mob の item・loot table・Mob スキル依存を、読込済み定義へ照合する。
     */
    @Test
    void localMobDependenciesMustExist() throws Exception {
        String withDependencies = MOB_YAML + """
                drops:
                  items:
                    - itemId:
                        ref: item:test_item
                  lootTable:
                    ref: loot_table:test_loot
                ai:
                  combat:
                    skills:
                      - id: test_skill
                """;
        writeFixture(withDependencies, SPAWNER_YAML);
        LocalMasterOverlay overlay = new LocalMasterOverlay(temporaryPluginData, true);
        overlay.replaceSnapshot(overlay.loadSnapshot());
        assertThrows(IllegalStateException.class,
                () -> overlay.validateMobDependencies(id -> false, id -> true, id -> true));
        assertThrows(IllegalStateException.class,
                () -> overlay.validateMobDependencies(id -> true, id -> false, id -> true));
        assertThrows(IllegalStateException.class,
                () -> overlay.validateMobDependencies(id -> true, id -> true, id -> false));
        assertTrue(overlay.currentSnapshot().mobs().containsKey("test_enemy"));
    }

    private void writeFixture(String mob, String spawner) throws Exception {
        Path root = temporaryPluginData.resolve("local-master-overlay");
        Path mobFile = root.resolve(MOB);
        Path spawnerFile = root.resolve(SPAWNER);
        Files.createDirectories(mobFile.getParent());
        Files.createDirectories(spawnerFile.getParent());
        Files.writeString(mobFile, mob, StandardCharsets.UTF_8);
        Files.writeString(spawnerFile, spawner, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("manifest.yml"), """
                schemaVersion: 1
                files:
                  - path: %s
                    sha256: %s
                  - path: %s
                    sha256: %s
                """.formatted(MOB, hash(mob), SPAWNER, hash(spawner)), StandardCharsets.UTF_8);
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
