package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReceptionDisplayEntityTest {
    private static final long MARKER = 0x4153545245435054L;

    @Test
    void decodesBothStylesAndYawWithoutChangingEntityIdBits() {
        assertEquals(new ReceptionDisplayEntity.Frame(0, 0),
            ReceptionDisplayEntity.decode(new UUID(MARKER, 0x0000_0000_1234_5678L), 0x1234_5678));
        assertEquals(new ReceptionDisplayEntity.Frame(1, 3599),
            ReceptionDisplayEntity.decode(new UUID(MARKER, 0x000e_0f01_fedc_ba98L), 0xfedc_ba98));
    }

    @Test
    void rejectsAnUnknownMarkerOrAnEntityIdMismatch() {
        assertNull(ReceptionDisplayEntity.decode(new UUID(0L, 0x1234L), 0x1234));
        assertNull(ReceptionDisplayEntity.decode(new UUID(MARKER, 0x1234L), 0x4321));
    }

    @Test
    void rejectsUnsupportedStylesYawAndReservedBits() {
        assertNull(ReceptionDisplayEntity.decode(new UUID(MARKER, 0x0000_0002_0000_1234L), 0x1234));
        assertNull(ReceptionDisplayEntity.decode(new UUID(MARKER, 0x000e_1001_0000_1234L), 0x1234));
        assertNull(ReceptionDisplayEntity.decode(new UUID(MARKER, 0x0100_0000_0000_1234L), 0x1234));
    }
}
