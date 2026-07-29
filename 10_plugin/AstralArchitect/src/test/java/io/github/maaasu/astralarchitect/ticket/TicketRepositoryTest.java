package io.github.maaasu.astralarchitect.ticket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesReadsTrashesAndRestoresTicket() throws Exception {
        TicketRepository repository = new TicketRepository(temporaryDirectory);
        repository.initialize();
        String id = "20260101-000000-abcdef12";
        Path ticketPath = repository.createTicketDirectory(id);
        TicketMetadata metadata = metadata(id).created("source", "candidate", "2026-01-01T00:01:00Z");
        repository.write(ticketPath, metadata);

        assertEquals(TicketState.CREATED, repository.read(id).state());
        repository.moveToTrash(id, Instant.parse("2026-01-01T00:02:00Z"));
        assertThrows(Exception.class, () -> repository.read(id));

        TicketMetadata restored = repository.restoreFromTrash(id, Instant.parse("2026-01-01T00:03:00Z"));
        assertEquals(TicketState.CREATED, restored.state());
        assertEquals(TicketState.CREATED, repository.read(id).state());
    }

    @Test
    void snapshotsCandidateAndHashesBytes() throws Exception {
        TicketRepository repository = new TicketRepository(temporaryDirectory);
        repository.initialize();
        Path ticketPath = repository.createTicketDirectory("20260101-000000-abcdef12");
        byte[] content = "schematic-content".getBytes(StandardCharsets.UTF_8);
        Files.write(ticketPath.resolve(TicketFiles.SOURCE_SCHEMATIC), content);

        repository.initializeCandidate(ticketPath);
        repository.snapshotAppliedCandidate(ticketPath);

        assertEquals(repository.sha256(ticketPath.resolve(TicketFiles.SOURCE_SCHEMATIC)),
                repository.sha256(ticketPath.resolve(TicketFiles.CANDIDATE_SCHEMATIC)));
        assertTrue(Files.exists(ticketPath.resolve(TicketFiles.APPLIED_SCHEMATIC)));
    }

    @Test
    void rejectsUnsafeTicketIdsAndPurgesExpiredTrash() throws Exception {
        TicketRepository repository = new TicketRepository(temporaryDirectory);
        repository.initialize();
        assertFalse(TicketRepository.isSafeTicketId("../escape"));
        assertThrows(Exception.class, () -> repository.createTicketDirectory("../escape"));

        String id = "20260101-000000-abcdef12";
        Path ticketPath = repository.createTicketDirectory(id);
        repository.write(ticketPath, metadata(id));
        Path trashPath = repository.moveToTrash(id, Instant.parse("2026-01-01T00:00:00Z"));
        Files.setLastModifiedTime(trashPath, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));

        int removed = repository.purgeExpiredTrash(
                Duration.ofDays(7),
                Instant.parse("2026-01-09T00:00:00Z"));
        assertEquals(1, removed);
        assertFalse(Files.exists(temporaryDirectory.resolve(".locks").resolve(id + ".lock")));
    }

    @Test
    void serializesCandidateAccessWithManagedLockFile() throws Exception {
        TicketRepository repository = new TicketRepository(temporaryDirectory);
        repository.initialize();
        String id = "20260101-000000-abcdef12";
        Path ticketPath = repository.createTicketDirectory(id);

        assertFalse(Files.exists(ticketPath.resolve(".candidate.lock")));
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve(".locks").resolve(id + ".lock")));
        try (TicketFileLock first = repository.acquireCandidateLock(ticketPath)) {
            first.requireHeld();
            assertThrows(IOException.class, () -> repository.acquireCandidateLock(ticketPath));
        }
        try (TicketFileLock second = repository.acquireCandidateLock(ticketPath)) {
            second.requireHeld();
        }
    }

    @Test
    void serializesWorkersAcrossPluginInstances() throws Exception {
        TicketRepository firstRepository = new TicketRepository(temporaryDirectory);
        TicketRepository secondRepository = new TicketRepository(temporaryDirectory);
        firstRepository.initialize();
        secondRepository.initialize();

        try (TicketFileLock first = firstRepository.acquireWorkerLock()) {
            first.requireHeld();
            assertThrows(IOException.class, secondRepository::acquireWorkerLock);
        }
        try (TicketFileLock second = secondRepository.acquireWorkerLock()) {
            second.requireHeld();
        }
    }

    @Test
    void resumesTrashAfterMetadataWasWrittenBeforeMove() throws Exception {
        TicketRepository repository = new TicketRepository(temporaryDirectory);
        repository.initialize();
        String id = "20260101-000000-abcdef12";
        Path ticketPath = repository.createTicketDirectory(id);
        TicketMetadata created = metadata(id).created("source", "candidate", "2026-01-01T00:01:00Z");
        TicketMetadata interrupted = created.trashed("2026-01-01T00:02:00Z");
        repository.write(ticketPath, interrupted);

        repository.moveToTrash(id, Instant.parse("2026-01-01T00:03:00Z"));

        TicketMetadata trashed = repository.readLatestTrash(id);
        assertEquals(TicketState.TRASHED, trashed.state());
        assertEquals(TicketState.CREATED, trashed.stateBeforeTrash());
        assertEquals("2026-01-01T00:02:00Z", trashed.deletedAt());
    }

    @Test
    void resumesRestoreAfterMetadataWasWrittenBeforeMove() throws Exception {
        TicketRepository repository = new TicketRepository(temporaryDirectory);
        repository.initialize();
        String id = "20260101-000000-abcdef12";
        Path ticketPath = repository.createTicketDirectory(id);
        TicketMetadata applied = metadata(id)
                .created("source", "candidate", "2026-01-01T00:01:00Z")
                .ready("candidate", 1L, "2026-01-01T00:02:00Z")
                .applied("candidate", "2026-01-01T00:03:00Z");
        repository.write(ticketPath, applied);
        Path trashPath = repository.moveToTrash(id, Instant.parse("2026-01-01T00:04:00Z"));
        TicketMetadata interrupted = repository.readLatestTrash(id)
                .restored("2026-01-01T00:05:00Z");
        repository.write(trashPath, interrupted);

        TicketMetadata restored = repository.restoreFromTrash(
                id,
                Instant.parse("2026-01-01T00:06:00Z"));

        assertEquals(TicketState.APPLIED, restored.state());
        assertEquals("candidate", restored.appliedCandidateSha256());
        assertEquals(TicketState.APPLIED, repository.read(id).state());
    }

    private static TicketMetadata metadata(String id) {
        return TicketMetadata.creating(
                id,
                "bridge",
                "00000000-0000-0000-0000-000000000001",
                "builder",
                "00000000-0000-0000-0000-000000000002",
                "world",
                new TicketBounds(new BlockPosition(0, 0, 0), new BlockPosition(0, 0, 0)),
                new BlockPosition(0, 0, 0),
                "minecraft:stone",
                1L,
                "1.21.11",
                "2.15.2",
                "2026-01-01T00:00:00Z");
    }
}
