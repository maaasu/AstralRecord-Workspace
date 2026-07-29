package io.github.maaasu.astralarchitect.ticket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketMetadataTest {

    @Test
    void preservesLifecycleHashesAndRestoresPreviousState() {
        TicketMetadata creating = createMetadata();
        TicketMetadata created = creating.created("source-hash", "initial-hash", "2026-01-01T00:01:00Z");
        TicketMetadata ready = created.ready("candidate-hash", 12L, "2026-01-01T00:02:00Z");
        TicketMetadata applying = ready.applying("candidate-hash", "2026-01-01T00:03:00Z");
        TicketMetadata applied = applying.applied("candidate-hash", "2026-01-01T00:04:00Z");
        TicketMetadata rollingBack = applied.rollingBack("2026-01-01T00:05:00Z");
        TicketMetadata rolledBack = rollingBack.rolledBack("2026-01-01T00:06:00Z");
        TicketMetadata readyAgain = rolledBack.ready("candidate-hash-2", 9L, "2026-01-01T00:07:00Z");
        TicketMetadata applyingAgain = readyAgain.applying("candidate-hash-2", "2026-01-01T00:08:00Z");
        TicketMetadata appliedAgain = applyingAgain.applied("candidate-hash-2", "2026-01-01T00:09:00Z");
        TicketMetadata trashed = appliedAgain.trashed("2026-01-01T00:10:00Z");
        TicketMetadata restored = trashed.restored("2026-01-01T00:11:00Z");

        assertEquals(TicketState.APPLIED, restored.state());
        assertNull(restored.stateBeforeTrash());
        assertEquals("source-hash", restored.sourceSha256());
        assertEquals("candidate-hash-2", restored.appliedCandidateSha256());
        assertEquals(9L, restored.changedBlockCount());
    }

    @Test
    void validatesOnlyCandidateEditingStates() {
        assertTrue(TicketState.CREATED.canValidate());
        assertTrue(TicketState.READY.canValidate());
        assertTrue(TicketState.ROLLED_BACK.canValidate());
        assertFalse(TicketState.APPLYING.canValidate());
        assertFalse(TicketState.APPLIED.canValidate());
        assertFalse(TicketState.ROLLING_BACK.canValidate());
    }

    private static TicketMetadata createMetadata() {
        return TicketMetadata.creating(
                "20260101-000000-abcdef12",
                "bridge",
                "00000000-0000-0000-0000-000000000001",
                "builder",
                "00000000-0000-0000-0000-000000000002",
                "world",
                new TicketBounds(new BlockPosition(0, 0, 0), new BlockPosition(2, 2, 2)),
                new BlockPosition(1, 1, 1),
                "minecraft:gold_block",
                27L,
                "1.21.11",
                "2.15.2",
                "2026-01-01T00:00:00Z");
    }
}
