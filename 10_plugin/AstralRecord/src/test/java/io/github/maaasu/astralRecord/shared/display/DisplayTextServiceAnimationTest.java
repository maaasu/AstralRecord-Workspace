package io.github.maaasu.astralRecord.shared.display;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayTextServiceAnimationTest {

    @Test
    void damageAnimationUsesFewClientInterpolatedKeyframes() {
        List<DisplayAnimationFrame> frames = DisplayTextService.damageAnimationFrames("&c10", 0.3D, -0.2D);

        assertEquals(6, frames.size());
        assertTrue(frames.stream().allMatch(frame -> frame.durationTicks() == 4L));
        assertEquals(0.3D, frames.getLast().offset().getX(), 0.0001D);
        assertEquals(-0.2D, frames.getLast().offset().getZ(), 0.0001D);
        assertTrue(frames.stream().mapToDouble(frame -> frame.offset().getY()).max().orElseThrow()
                > frames.getLast().offset().getY());
    }
}
