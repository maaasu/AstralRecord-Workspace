package io.github.maaasu.astralRecord.infrastructure.api;

import io.github.maaasu.astralRecord.feature.account.repository.ProgressionPresetRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeFilebaseRepositoryTest {
    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## 共通基盤の設定スナップショットと入力正規化 > ### APIマスタ原稿スナップショット
     * 検証契約: 準備中のsnapshotは入れ子のaction内だけ優先し例外後も公開済みsnapshotを復元する。
     */
    @Test
    void scopedPreparationRestoresPublishedSnapshotOnSuccessAndFailure() {
        RuntimeFilebaseRepository repository = new RuntimeFilebaseRepository();
        RuntimeFilebaseSnapshot published = new RuntimeFilebaseSnapshot(List.of());
        RuntimeFilebaseSnapshot outer = new RuntimeFilebaseSnapshot(List.of());
        RuntimeFilebaseSnapshot inner = new RuntimeFilebaseSnapshot(List.of());
        repository.replaceSnapshot(published);
        repository.withSnapshot(outer, () -> {
            assertSame(outer, repository.currentSnapshot());
            assertThrows(IllegalStateException.class, () -> repository.withSnapshot(inner, () -> {
                assertSame(inner, repository.currentSnapshot());
                throw new IllegalStateException("fixture");
            }));
            assertSame(outer, repository.currentSnapshot());
            return null;
        });
        assertSame(published, repository.currentSnapshot());
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## 共通基盤の設定スナップショットと入力正規化 > ### APIマスタ原稿スナップショット
     * 検証契約: APIが配信する進行プリセットのYAML原稿をスナップショットから取得できる。
     */
    @Test
    void acceptsProgressionPresetYamlFromApi() {
        var snapshot = RuntimeFilebaseRepository.parseSnapshot("""
                {"schemaVersion":1,"files":[
                  {"path":"90.shared.progression_preset/v1.adventurer_magic.yml","content":"id: adventurer_magic"}
                ]}
                """);

        var entries = snapshot.files("90.shared.progression_preset");
        assertEquals(1, entries.size());
        assertEquals("v1.adventurer_magic.yml", entries.getFirst().name());
        assertEquals("id: adventurer_magic", entries.getFirst().content());
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## 共通基盤の設定スナップショットと入力正規化 > ### APIマスタ原稿スナップショット
     * 検証契約: 新Pluginは旧APIの進行プリセットパスを受け付け、新パスがあれば新カテゴリだけを読み込む。
     */
    @Test
    void progressionPresetUsesNewDirectoryBeforeLegacyInput() {
        String oldPath = "36.features.progression_preset/v1.adventurer_magic.yml";
        String newPath = "90.shared.progression_preset/v1.adventurer_magic.yml";
        String oldContent = preset("Old");
        String newContent = preset("New");
        var legacy = RuntimeFilebaseRepository.parseSnapshot("""
                {"schemaVersion":1,"files":[
                  {"path":"36.features.progression_preset/v1.adventurer_magic.yml","content":"id: legacy"}
                ]}
                """);
        assertEquals(1, legacy.files("36.features.progression_preset").size());
        var oldOnly = new RuntimeFilebaseSnapshot(List.of(new RuntimeFilebaseSnapshot.Entry(oldPath, oldContent)));
        assertEquals("Old", ProgressionPresetRepository.load(oldOnly).getFirst().name());
        var newOnly = new RuntimeFilebaseSnapshot(List.of(new RuntimeFilebaseSnapshot.Entry(newPath, newContent)));
        assertEquals("New", ProgressionPresetRepository.load(newOnly).getFirst().name());
        var both = new RuntimeFilebaseSnapshot(List.of(
                new RuntimeFilebaseSnapshot.Entry(oldPath, oldContent),
                new RuntimeFilebaseSnapshot.Entry(newPath, newContent)));
        var selected = ProgressionPresetRepository.load(both);
        assertEquals(1, selected.size());
        assertEquals("New", selected.getFirst().name());
    }

    private static String preset(String name) {
        return """
                schemaVersion: 1
                type: PROGRESSION_PRESET
                id: adventurer_magic
                name: %s
                classId: adventurer
                buildId: magic
                levels:
                  - level: 1
                    classLevel: 1
                    nodes: []
                    equipment: [{ slot: MAIN_HAND, item: 'item:sword' }]
                    skills: [{ slot: 1, skill: 'skill:slash' }]
                """.formatted(name);
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## 共通基盤の設定スナップショットと入力正規化 > ### APIマスタ原稿スナップショット
     * 検証契約: 未取得および不正API応答を空マスタとして公開せず共有snapshotを維持する。
     */
    @Test
    void invalidResponsesNeverReplacePublishedSnapshot() {
        RuntimeFilebaseRepository repository = new RuntimeFilebaseRepository();
        assertThrows(IllegalStateException.class, repository::currentSnapshot);
        RuntimeFilebaseSnapshot published = new RuntimeFilebaseSnapshot(List.of());
        repository.replaceSnapshot(published);
        for (String json : List.of("{}", "{\"schemaVersion\":2,\"files\":[]}",
                "{\"schemaVersion\":1,\"files\":[{\"path\":\"../config.yml\",\"content\":\"\"}]}",
                "{\"schemaVersion\":1,\"files\":[{\"path\":\"51.features.quest/a.yml\",\"content\":2}]}")) {
            assertThrows(IllegalStateException.class, () -> RuntimeFilebaseRepository.parseSnapshot(json));
            assertSame(published, repository.currentSnapshot());
        }
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## 共通基盤の設定スナップショットと入力正規化 > ### APIマスタ原稿スナップショット
     * 検証契約: APIマスタ原稿の相対パスは一意かつ安全で、必須原稿の欠落とYAML構文不正を拒否する。
     */
    @Test
    void duplicateUnsafeMissingAndMalformedEntriesAreRejected() {
        var entry = new RuntimeFilebaseSnapshot.Entry("51.features.quest/a.yml", "value: fixture");
        assertThrows(IllegalArgumentException.class, () -> new RuntimeFilebaseSnapshot(List.of(entry, entry)));
        for (String path : List.of("/root", "../a", "a/../b", "a//b", "C:/a", "a\\b")) {
            assertThrows(IllegalArgumentException.class, () -> new RuntimeFilebaseSnapshot.Entry(path, ""));
        }
        var snapshot = new RuntimeFilebaseSnapshot(List.of(entry));
        assertThrows(IllegalStateException.class, () -> snapshot.require("missing"));
        assertThrows(IllegalStateException.class, () ->
                new RuntimeFilebaseSnapshot.Entry("51.features.quest/invalid.yml", "value: [").yaml());
        assertEquals("fixture", entry.yaml().getString("value"));
        assertEquals(List.of(entry), snapshot.files("51.features.quest"));
    }
}
