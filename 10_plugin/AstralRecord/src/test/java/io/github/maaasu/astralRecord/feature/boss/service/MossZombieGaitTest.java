package io.github.maaasu.astralRecord.feature.boss.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MossZombieGaitTest {
    // Presentation follows actual distance, including movement below the old 0.008-block/tick dead zone.
    @Test
    void creepingStillShowsBothFeetWithoutSkippingPoses() {
        var gait = new MossZombieGait();
        int min = 0;
        int max = 0;
        int previous = 0;
        for (int tick = 0; tick < 250; tick++) {
            gait.advance(0.004, false);
            int frame = gait.frame();
            assertTrue(Math.abs(frame - previous) <= 2);
            min = Math.min(min, frame);
            max = Math.max(max, frame);
            previous = frame;
        }
        assertTrue(max >= 6);
        assertTrue(min <= -6);
    }

    @Test
    void oneBlockCompletesTheCycleAtEitherWalkingSpeed() {
        var slow = new MossZombieGait();
        var slower = new MossZombieGait();
        for (int i = 0; i < 50; i++) slow.advance(0.020, false);
        for (int i = 0; i < 100; i++) slower.advance(0.010, false);
        assertEquals(0, slow.frame());
        assertEquals(0, slower.frame());
    }

    @Test
    void briefSampleGapHoldsFootThenStopsAndTeleportResets() {
        var gait = new MossZombieGait();
        for (int i = 0; i < 14; i++) gait.advance(0.02, false);
        assertTrue(gait.frame() >= 5);
        int foot = gait.frame();
        gait.advance(0, false);
        assertEquals(foot, gait.frame());
        for (int i = 0; i < 18; i++) gait.advance(0.0002, false);
        assertEquals(0, gait.frame());
        for (int i = 0; i < 12; i++) gait.advance(0.02, false);
        assertNotEquals(0, gait.frame());
        gait.advance(0, true);
        assertEquals(0, gait.frame());
    }
}
