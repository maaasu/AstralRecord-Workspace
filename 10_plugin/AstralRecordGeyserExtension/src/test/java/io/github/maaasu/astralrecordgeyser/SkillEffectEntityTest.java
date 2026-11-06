package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SkillEffectEntityTest {
    private static final long MARKER = 0x41535452534b4c31L;

    @Test
    void decodesStyleAndSizeIndependently() {
        assertEquals(new SkillEffectEntity.Frame(0, 1),
            SkillEffectEntity.decode(new UUID(MARKER, 0x400L)));
        assertEquals(new SkillEffectEntity.Frame(679, 255),
            SkillEffectEntity.decode(new UUID(MARKER, 0xffff_ffff_ffff_fea7L)));
    }

    @Test
    void rejectsUnknownMarkerUnsupportedStyleAndZeroSize() {
        assertNull(SkillEffectEntity.decode(new UUID(0L, 0x400L)));
        assertNull(SkillEffectEntity.decode(new UUID(MARKER, 0x6a8L)));
        assertNull(SkillEffectEntity.decode(new UUID(MARKER, 0x2a7L)));
    }
}
