package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SunbirdSkillEntityTest {
    @Test
    void decodesIndependentDimensionsAndIgnoresUpperBits() {
        // The sender uses 8 bits for the style and 12 bits per dimension in 1/32-block units.
        var uuid = new UUID(0x41535453554e4631L, 0xfffff001fff12005L);
        assertEquals(new SunbirdSkillEntity.Frame(5, 9f, 127.96875f, 0.03125f), SunbirdSkillEntity.decode(uuid));
    }

    @Test
    void rejectsUnknownMarkerStyleAndEmptyDimensions() {
        assertNull(SunbirdSkillEntity.decode(new UUID(0L, 0x00200200200L)));
        assertNull(SunbirdSkillEntity.decode(new UUID(0x41535453554e4631L, 0x00200200206L)));
        assertNull(SunbirdSkillEntity.decode(new UUID(0x41535453554e4631L, 0x00200200000L)));
    }
}
