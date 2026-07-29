package io.github.maaasu.astralRecord.feature.combat.service;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SuperStarCriticalProjectileServiceTest {

    @Test
    void initialDirectionUsesFortyFiveDegreeElevationAndRandomAzimuthPlane() {
        Vector direction = SuperStarCriticalProjectileService.initialDirection(0.0D);

        assertEquals(1.0D, direction.length(), 0.0001D);
        assertEquals(Math.sqrt(0.5D), direction.getX(), 0.0001D);
        assertEquals(Math.sqrt(0.5D), direction.getY(), 0.0001D);
        assertEquals(0.0D, direction.getZ(), 0.0001D);
    }

    @Test
    void initialFlightRangeMatchesConfiguredHalfToOneSecond() {
        double minimumDistance = SuperStarCriticalProjectileService.INITIAL_SPEED_PER_TICK
                * SuperStarCriticalProjectileService.MIN_INITIAL_TICKS;
        double maximumDistance = SuperStarCriticalProjectileService.INITIAL_SPEED_PER_TICK
                * SuperStarCriticalProjectileService.MAX_INITIAL_TICKS;

        assertEquals(1.0D, minimumDistance, 0.0001D);
        assertEquals(2.0D, maximumDistance, 0.0001D);
    }
}
