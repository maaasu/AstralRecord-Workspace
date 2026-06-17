package io.github.maaasu.astralRecord.shared.display;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayTextServiceTest {

    @Test
    void damageAnimationFramesRiseAndDriftAwayFromOrigin() {
        List<DisplayAnimationFrame> frames = DisplayTextService.damageAnimationFrames("&c120", 0.32D, -0.22D);

        assertEquals(12, frames.size());
        assertEquals("&c120", frames.getFirst().text());
        assertEquals("&c120", frames.getLast().text());
        assertEquals(2L, frames.getFirst().durationTicks());

        assertTrue(frames.getFirst().offset().getY() > 0.0D);
        assertTrue(frames.getLast().offset().getY() > frames.getFirst().offset().getY());
        assertTrue(frames.getLast().offset().getX() > frames.getFirst().offset().getX());
        assertTrue(frames.getLast().offset().getZ() < frames.getFirst().offset().getZ());
    }
}
