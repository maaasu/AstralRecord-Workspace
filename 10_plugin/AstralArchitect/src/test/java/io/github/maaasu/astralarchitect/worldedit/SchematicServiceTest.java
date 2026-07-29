package io.github.maaasu.astralarchitect.worldedit;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchematicServiceTest {

    @Test
    void acceptsCompleteVarIntBlockData() {
        byte[] data = new byte[]{0, 1, (byte) 0xAC, 0x02};

        assertDoesNotThrow(() -> SchematicService.validateBlockData(
                Path.of("candidate.schem"),
                data,
                3L,
                Set.of(0, 1, 300)));
    }

    @Test
    void rejectsMissingPaletteReference() {
        byte[] data = new byte[]{0, 2};

        assertThrows(CandidateValidationException.class, () -> SchematicService.validateBlockData(
                Path.of("candidate.schem"),
                data,
                2L,
                Set.of(0, 1)));
    }

    @Test
    void rejectsIncompleteOrWrongLengthBlockData() {
        assertThrows(CandidateValidationException.class, () -> SchematicService.validateBlockData(
                Path.of("candidate.schem"),
                new byte[]{(byte) 0x80},
                1L,
                Set.of(0)));
        assertThrows(CandidateValidationException.class, () -> SchematicService.validateBlockData(
                Path.of("candidate.schem"),
                new byte[]{0},
                2L,
                Set.of(0)));
    }
}
