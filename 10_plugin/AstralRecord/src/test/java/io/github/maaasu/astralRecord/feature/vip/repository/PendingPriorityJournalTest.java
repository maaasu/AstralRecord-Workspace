package io.github.maaasu.astralRecord.feature.vip.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PendingPriorityJournalTest {
    @TempDir Path directory;

    /**
     * 未開始の優先消費だけが再起動後の返却対象として復元される。
     */
    @Test void pendingOperationsSurviveRestartAndCompletionIsDurable() {
        Path file = directory.resolve("pending.properties");
        UUID operation = UUID.randomUUID(), account = UUID.randomUUID();
        var journal = new PendingPriorityJournal(file);
        journal.add(operation, account);
        journal.add(operation, account);
        var restarted = new PendingPriorityJournal(file);
        assertEquals(account, restarted.pending().get(operation));
        restarted.complete(operation);
        restarted.complete(operation);
        assertTrue(new PendingPriorityJournal(file).pending().isEmpty());
    }

    /**
     * 保存失敗は成功扱いにせず、元の返却対象を失わない。
     */
    @Test void failedReplacementPreservesPreviousJournal() throws Exception {
        Path file = directory.resolve("pending.properties");
        UUID first = UUID.randomUUID(), account = UUID.randomUUID();
        var journal = new PendingPriorityJournal(file);
        journal.add(first, account);
        Files.createDirectory(directory.resolve("pending.properties.tmp"));
        assertThrows(java.io.UncheckedIOException.class, () -> journal.add(UUID.randomUUID(), account));
        assertEquals(journal.pending(), new PendingPriorityJournal(file).pending());
        assertEquals(1, journal.pending().size());
    }
}
