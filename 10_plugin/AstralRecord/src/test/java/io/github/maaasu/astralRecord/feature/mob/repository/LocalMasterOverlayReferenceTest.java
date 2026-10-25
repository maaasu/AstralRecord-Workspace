package io.github.maaasu.astralRecord.feature.mob.repository;

import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.infrastructure.api.LocalMasterOverlay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalMasterOverlayReferenceTest {
    @TempDir Path pluginData;

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## 共通基盤の設定スナップショットと入力正規化 > ### Dev ローカルマスタオーバーレイ
     * 検証契約: ローカルスポナーが存在しない Mob レベルを要求したら、既定レベルへの暗黙フォールバックを拒否する。
     */
    @Test
    void localSpawnerRequiresExactMobLevel() {
        MobTemplate mob = new MobRepository().parseTemplate(JsonParser.parseString("""
                {"id":"test_enemy","category":"ENEMY","entityType":"PIG","name":"Test",
                 "level":15,"levels":[{"level":15},{"level":16}]}
                """).getAsJsonObject());
        LocalMasterOverlay overlay = new LocalMasterOverlay(pluginData, true);
        overlay.replaceSnapshot(snapshot(45));
        assertThrows(IllegalStateException.class,
                () -> overlay.validateReferences(Map.of("test_enemy", mob)));
        overlay.replaceSnapshot(snapshot(16));
        assertDoesNotThrow(() -> overlay.validateReferences(Map.of("test_enemy", mob)));
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## 共通基盤の設定スナップショットと入力正規化 > ### Dev ローカルマスタオーバーレイ
     * 検証契約: 遅延 Mob ID 検索はローカル原稿を先に解析し、共有 API への照会なしに返す。
     */
    @Test
    void localMobIdLookupUsesLocalYaml() {
        String path = "40.features.mob/enemy/v1.test_enemy.yml";
        String yaml = """
                schemaVersion: 1
                id: test_enemy
                type: MOB
                category: ENEMY
                name: Local
                level: 15
                entityType: PIG
                """;
        LocalMasterOverlay overlay = new LocalMasterOverlay(pluginData, true);
        overlay.replaceSnapshot(new LocalMasterOverlay.Snapshot(
                Map.of("test_enemy", new LocalMasterOverlay.Entry(path, yaml)), Map.of()));
        assertEquals("test_enemy", new MobRepository(overlay).findById("test_enemy").id());
    }

    private static LocalMasterOverlay.Snapshot snapshot(int level) {
        String path = "41.features.mob.spawner/v1.test_spawner.yml";
        String yaml = """
                schemaVersion: 1
                id: test_spawner
                spawnMobs:
                  - mobId: mob:test_enemy
                    level: %d
                """.formatted(level);
        return new LocalMasterOverlay.Snapshot(Map.of(), Map.of(
                "test_spawner", new LocalMasterOverlay.Entry(path, yaml)));
    }
}
