package io.github.maaasu.astralRecord.feature.combat.service;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SuperStarCriticalProjectileServiceTest {

    @Test
    void initialDirectionUsesRequestedElevationAndAzimuth() {
        double elevation = Math.toRadians(30.0D);
        Vector direction = SuperStarCriticalProjectileService.initialDirection(0.0D, elevation);

        assertEquals(1.0D, direction.length(), 0.0001D);
        assertEquals(Math.cos(elevation), direction.getX(), 0.0001D);
        assertEquals(Math.sin(elevation), direction.getY(), 0.0001D);
        assertEquals(0.0D, direction.getZ(), 0.0001D);
    }

    @Test
    void initialFlightRangeMatchesConfiguredRandomSpeedAndDuration() {
        double minimumDistance = SuperStarCriticalProjectileService.MIN_INITIAL_SPEED_PER_TICK
                * SuperStarCriticalProjectileService.MIN_INITIAL_TICKS;
        double maximumDistance = SuperStarCriticalProjectileService.MAX_INITIAL_SPEED_PER_TICK
                * SuperStarCriticalProjectileService.MAX_INITIAL_TICKS;

        assertEquals(1.6D, minimumDistance, 0.0001D);
        assertEquals(4.8D, maximumDistance, 0.0001D);
        assertEquals(0.35D, SuperStarCriticalProjectileService.HOMING_SPEED_PER_TICK, 0.0001D);
    }
}
