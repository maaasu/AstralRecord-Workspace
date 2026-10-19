package io.github.maaasu.astralRecord.feature.defense;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** 保存境界の汎用契約を実ファイルで検証します。 */
class DefenseStateStoreTest {
    @TempDir Path directory;

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/42-defense/42_3-サービス.md
     * 章・見出し: # 42_3-サービス > ## 報酬・永続化 > ### 状態ファイルの読込・置換保存
     * 検証契約: 初回は空として読み込み、UTF8のJSONを新しいスナップショットで置換保存する。
     */
    @Test
    void replacesSnapshotWithoutRetainingRemovedKeys() throws IOException {
        DefenseStateStore store = new DefenseStateStore(directory);
        assertTrue(store.load().isEmpty());
        JsonObject old = new JsonObject();
        old.addProperty("removed", "破棄される状態");
        store.save(old);
        JsonObject replacement = new JsonObject();
        replacement.addProperty("retained", "再送を待つ状態");
        store.save(replacement);

        assertEquals(replacement, new DefenseStateStore(directory).load());
        assertFalse(Files.exists(directory.resolve("defense_runtime.json.tmp")));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/42-defense/42_3-サービス.md
     * 章・見出し: # 42_3-サービス > ## 報酬・永続化 > ### 状態ファイルの読込・置換保存
     * 検証契約: 破損JSONを読み込んだ場合は例外とし、元ファイルの内容を保持する。
     */
    @Test
    void preservesCorruptJournalInsteadOfTreatingItAsEmpty() throws IOException {
        Path file = directory.resolve("defense_runtime.json");
        Files.writeString(file, "{broken", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> new DefenseStateStore(directory).load());
        assertEquals("{broken", Files.readString(file, StandardCharsets.UTF_8));
    }
}
