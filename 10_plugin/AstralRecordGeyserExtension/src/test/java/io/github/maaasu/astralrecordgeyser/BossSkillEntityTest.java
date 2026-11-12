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
        var uuid = new UUID(seleneaMarker, 0x0200200200cL);
        assertEquals(new BossSkillEntity.Frame(12, 1f, 1f, 1f), BossSkillEntity.decode(uuid, seleneaMarker, 12));
        assertNull(BossSkillEntity.decode(uuid, SUNBIRD_MARKER, SUNBIRD_MAX_STYLE));
        assertNull(BossSkillEntity.decode(new UUID(seleneaMarker, 0x0200200200dL), seleneaMarker, 12));
    }

    @Test
    void readsNyxeronParamFromBits44To55() {
        long nyxeronMarker = 0x4153544e59584631L;
        // style 22, width 1/32 * 29, length 1/32 * 192, height 1/32 * 51, param 0x012 (2 gaps, half 1), id 0xab.
        long bits = 22L | (29L << 8) | (192L << 20) | (51L << 32) | (0x012L << 44) | (0xabL << 56);
        var uuid = new UUID(nyxeronMarker, bits);
        assertEquals(new BossSkillEntity.Frame(22, 29 / 32f, 6f, 51 / 32f), BossSkillEntity.decode(uuid, nyxeronMarker, 23));
        assertEquals(0x012, BossSkillEntity.param(uuid));
        assertEquals(0xfff, BossSkillEntity.param(new UUID(nyxeronMarker, 0xffffffffffffffffL)));
        assertNull(BossSkillEntity.decode(new UUID(nyxeronMarker, bits + 2), nyxeronMarker, 23));
    }

    @Test
    void rejectsUnknownMarkerStyleAndEmptyDimensions() {
        assertNull(BossSkillEntity.decode(new UUID(0L, 0x00200200200L), SUNBIRD_MARKER, SUNBIRD_MAX_STYLE));
        assertNull(BossSkillEntity.decode(new UUID(SUNBIRD_MARKER, 0x00200200206L), SUNBIRD_MARKER, SUNBIRD_MAX_STYLE));
        assertNull(BossSkillEntity.decode(new UUID(SUNBIRD_MARKER, 0x00200200000L), SUNBIRD_MARKER, SUNBIRD_MAX_STYLE));
    }
}
