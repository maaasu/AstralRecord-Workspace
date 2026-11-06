package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BossSkillEntityTest {
    private static final long SUNBIRD_MARKER = 0x41535453554e4631L;
    private static final int SUNBIRD_MAX_STYLE = 5;

    @Test
    void decodesIndependentDimensionsAndIgnoresUpperBits() {
        // The sender uses 8 bits for the style and 12 bits per dimension in 1/32-block units.
        var uuid = new UUID(SUNBIRD_MARKER, 0xfffff001fff12005L);
        assertEquals(new BossSkillEntity.Frame(5, 9f, 127.96875f, 0.03125f), BossSkillEntity.decode(uuid, SUNBIRD_MARKER, SUNBIRD_MAX_STYLE));
    }

    @Test
    void decodesSeleneaStylesAndRejectsTheNextStyle() {
        long seleneaMarker = 0x41535453454c4631L;
        var uuid = new UUID(seleneaMarker, 0x0200200200aL);
        assertEquals(new BossSkillEntity.Frame(10, 1f, 1f, 1f), BossSkillEntity.decode(uuid, seleneaMarker, 10));
        assertNull(BossSkillEntity.decode(uuid, SUNBIRD_MARKER, SUNBIRD_MAX_STYLE));
        assertNull(BossSkillEntity.decode(new UUID(seleneaMarker, 0x0200200200bL), seleneaMarker, 10));
    }

    @Test
    void rejectsUnknownMarkerStyleAndEmptyDimensions() {
        assertNull(BossSkillEntity.decode(new UUID(0L, 0x00200200200L), SUNBIRD_MARKER, SUNBIRD_MAX_STYLE));
        assertNull(BossSkillEntity.decode(new UUID(SUNBIRD_MARKER, 0x00200200206L), SUNBIRD_MARKER, SUNBIRD_MAX_STYLE));
        assertNull(BossSkillEntity.decode(new UUID(SUNBIRD_MARKER, 0x00200200000L), SUNBIRD_MARKER, SUNBIRD_MAX_STYLE));
    }
}
