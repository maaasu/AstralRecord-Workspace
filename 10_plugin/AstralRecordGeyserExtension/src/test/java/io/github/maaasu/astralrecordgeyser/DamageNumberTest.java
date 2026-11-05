package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DamageNumberTest {
    private static final long UUID_PREFIX = 0xa57ad00dfffffff0L;
    private static final long SIDECAR = 1L << 52;

    @Test
    void decodesLargestShieldDisplayWithoutOverflowingThePackGlyphs() {
        var number = DamageNumber.decode(new UUID(UUID_PREFIX, SIDECAR | (2L << 50) | (2L << 48) | 999_999_999_999L), -1);
        assertEquals(1.35f, number.scale());
        assertEquals(DamageNumber.MAX_GLYPHS, number.glyphs().length);
        assertArrayEquals(new int[] {13, 11, 9, 9, 9, 10, 9, 9, 9, 10, 9, 9, 9, 10, 9, 9, 9}, number.glyphs());
    }

    @Test
    void decodesNewStylesWithoutChangingSizeOrSidecarBits() {
        for (int style = 3; style <= DamageNumber.MAX_STYLE; style++) {
            long bits = SIDECAR | (2L << 50) | ((style & 3L) << 48) | ((long) (style >>> 2) << 53);
            var number = DamageNumber.decode(new UUID(UUID_PREFIX, bits | 1_234L), -1);
            assertEquals(style, number.style());
            assertEquals(1.35f, number.scale());
            assertArrayEquals(new int[] {13, style == 8 ? 12 : 11, 1, 10, 2, 3, 4}, number.glyphs());
        }
    }

    @Test
    void decodesFallbackAndRejectsMalformedTagsOrEntityIds() {
        assertArrayEquals(new int[] {12, 0}, DamageNumber.decode(new UUID(UUID_PREFIX, 1L << 48), -16).glyphs());
        assertNull(DamageNumber.decode(new UUID(UUID_PREFIX, 0), -1));
        assertNull(DamageNumber.decode(new UUID(UUID_PREFIX, SIDECAR), -16));
        assertNull(DamageNumber.decode(new UUID(UUID_PREFIX, 1L << 40), -16));
        assertNull(DamageNumber.decode(new UUID(UUID_PREFIX, (2L << 53) | (1L << 48)), -16));
        assertNull(DamageNumber.decode(new UUID(UUID_PREFIX, 1_000_000_000_000L), -16));
        assertNull(DamageNumber.decode(UUID.randomUUID(), -16));
    }
}
