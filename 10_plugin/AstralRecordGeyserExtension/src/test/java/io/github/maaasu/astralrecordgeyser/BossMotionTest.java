package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BossMotionTest {
    private static final long SUNBIRD_MARKER = 0x41535453554e4d31L;
    private static final int SUNBIRD_MAX_MOTION = 3;
    private static final long SELENEA_MARKER = 0x41535453454c4d31L;
    private static final int SELENEA_MAX_MOTION = 4;

    @Test
    void decodesMotionAndInvulnerabilityIndependently() {
        assertEquals(new BossMotion.Frame(42, 2, true),
            BossMotion.decode(new UUID(SUNBIRD_MARKER, 0xdead01020000002aL), SUNBIRD_MARKER, SUNBIRD_MAX_MOTION));
        assertEquals(new BossMotion.Frame(Integer.MAX_VALUE, 3, false),
            BossMotion.decode(new UUID(SUNBIRD_MARKER, 0x000000037fffffffL), SUNBIRD_MARKER, SUNBIRD_MAX_MOTION));
    }

    @Test
    void decodesSeleneaRecoveryWithoutAcceptingItAsSunbird() {
        var uuid = new UUID(SELENEA_MARKER, 0x0000010400000064L);
        assertEquals(new BossMotion.Frame(100, 4, true), BossMotion.decode(uuid, SELENEA_MARKER, SELENEA_MAX_MOTION));
        assertNull(BossMotion.decode(new UUID(SELENEA_MARKER, 0x0000000500000064L), SELENEA_MARKER, SELENEA_MAX_MOTION));
        assertNull(BossMotion.decode(uuid, SUNBIRD_MARKER, SUNBIRD_MAX_MOTION));
    }

    @Test
    void rejectsUnrecognizedMarkerMotionAndEntityId() {
        assertNull(BossMotion.decode(new UUID(0L, 42L), SUNBIRD_MARKER, SUNBIRD_MAX_MOTION));
        assertNull(BossMotion.decode(new UUID(SUNBIRD_MARKER, 0x000000040000002aL), SUNBIRD_MARKER, SUNBIRD_MAX_MOTION));
        assertNull(BossMotion.decode(new UUID(SUNBIRD_MARKER, 0L), SUNBIRD_MARKER, SUNBIRD_MAX_MOTION));
        assertNull(BossMotion.decode(new UUID(SUNBIRD_MARKER, 0x00000000ffffffffL), SUNBIRD_MARKER, SUNBIRD_MAX_MOTION));
    }
}
