package io.github.maaasu.astralRecord.feature.mutation.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PendingStateStoreTest {
    @TempDir Path directory;

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 1. save メソッド仕様 > ### ローカルファイルと再送
     * 検証契約: 再読込後も未受領JSONを同一内容で保持し、一人のACK削除で他者の未受領状態を削除しない。
     */
    @Test void restoresFrozenPayloadAndDeletesOnlyAcknowledgedSubject() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        PendingStateStore store = new PendingStateStore(directory);
        String payload = "{\"name\":\"強化済み\",\"revision\":42}";
        store.write(first, payload);
        store.write(second, "{\"revision\":17}");
        PendingStateStore reloaded = new PendingStateStore(directory);
        assertEquals(payload, reloaded.read(first));
        reloaded.delete(first);
        assertNull(reloaded.read(first));
        assertEquals("{\"revision\":17}", reloaded.read(second));
        assertEquals(java.util.List.of(second), reloaded.subjects());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 1. save メソッド仕様 > ### ローカルファイルと再送
     * 検証契約: 破損した未受領ファイルは例外を返して保持し、空状態としてロードを継続しない。
     */
    @Test void retainsCorruptFileForDiagnosis() throws Exception {
        UUID account = UUID.randomUUID();
        Path file = directory.resolve(account + ".bin");
        Files.write(file, new byte[] {0, 1});
        assertThrows(java.io.UncheckedIOException.class, () -> new PendingStateStore(directory).read(account));
        assertTrue(Files.exists(file));
    }
}
