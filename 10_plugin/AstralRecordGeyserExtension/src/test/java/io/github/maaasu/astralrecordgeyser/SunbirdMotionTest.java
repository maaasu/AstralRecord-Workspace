package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SunbirdMotionTest {
    private static final long MARKER = 0x41535453554e4d31L;

    @Test
    void decodesMotionAndInvulnerabilityIndependently() {
        assertEquals(new SunbirdMotion.Frame(42, 2, true),
            SunbirdMotion.decode(new UUID(MARKER, 0xdead01020000002aL)));
        assertEquals(new SunbirdMotion.Frame(Integer.MAX_VALUE, 3, false),
            SunbirdMotion.decode(new UUID(MARKER, 0x000000037fffffffL)));
    }

    @Test
    void rejectsUnrecognizedMarkerMotionAndEntityId() {
        assertNull(SunbirdMotion.decode(new UUID(0L, 42L)));
        assertNull(SunbirdMotion.decode(new UUID(MARKER, 0x000000040000002aL)));
        assertNull(SunbirdMotion.decode(new UUID(MARKER, 0L)));
        assertNull(SunbirdMotion.decode(new UUID(MARKER, 0x00000000ffffffffL)));
    }
}
