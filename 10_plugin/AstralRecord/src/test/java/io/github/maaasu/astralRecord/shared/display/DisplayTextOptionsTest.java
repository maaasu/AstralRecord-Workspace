package io.github.maaasu.astralRecord.shared.display;

import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisplayTextOptionsTest {

    @Test
    void constructorClampsValuesAndClonesOffset() {
        Vector originalOffset = new Vector(1.25D, 2.5D, 3.75D);

        DisplayTextOptions options = new DisplayTextOptions(
                "label",
                originalOffset,
                Display.Billboard.CENTER,
                0,
                0.0F,
                (byte) 0x7F,
                true,
                true,
                false,
                Color.WHITE,
                999,
                -10,
                false,
                15,
                15
        );

        originalOffset.setX(99.0D);

        assertNotSame(originalOffset, options.offset());
        assertEquals(1.25D, options.offset().getX());
        assertEquals(1, options.lineWidth());
        assertEquals(1.0F, options.viewRange());
        assertEquals(59, options.interpolationDuration());
        assertEquals(0, options.teleportDuration());
    }

    @Test
    void constructorRejectsHalfConfiguredBrightness() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DisplayTextOptions(
                        "label",
                        new Vector(),
                        Display.Billboard.CENTER,
                        160,
                        32.0F,
                        (byte) 0x7F,
                        false,
                        false,
                        false,
                        null,
                        1,
                        1,
                        false,
                        15,
                        null
                )
        );
    }
}
