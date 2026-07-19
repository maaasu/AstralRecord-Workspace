package io.github.maaasu.astralRecord.shared.interaction;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerInteractionRayTraceTest {
    private static final double EPSILON = 1.0E-9D;

    @Test
    void sphereEntryUsesNormalizedDirectionAndReturnsNearSurface() {
        PlayerInteractionRayTrace ray = PlayerInteractionRayTrace.create(
                new Vector(0.0D, 0.0D, 0.0D),
                new Vector(2.0D, 0.0D, 0.0D),
                10.0D
        );

        assertNotNull(ray);
        assertEquals(1.0D, ray.direction().length(), EPSILON);
        Double hitDistance = ray.sphereEntryDistance(new Vector(5.0D, 0.0D, 0.0D), 1.0D);
        assertNotNull(hitDistance);
        assertTrue(Double.isFinite(hitDistance));
        assertEquals(4.0D, hitDistance, EPSILON);
    }

    @Test
    void sphereContainingOriginReturnsZero() {
        PlayerInteractionRayTrace ray = assertValidRay(10.0D);

        assertEquals(0.0D, ray.sphereEntryDistance(new Vector(0.5D, 0.0D, 0.0D), 1.0D), EPSILON);
    }

    @Test
    void aabbEntryReturnsNearFaceAndZeroFromInside() {
        PlayerInteractionRayTrace ray = assertValidRay(10.0D);

        assertEquals(
                3.0D,
                ray.aabbEntryDistance(new BoundingBox(3.0D, -1.0D, -1.0D, 4.0D, 1.0D, 1.0D)),
                EPSILON
        );
        assertEquals(
                0.0D,
                ray.aabbEntryDistance(new BoundingBox(-1.0D, -1.0D, -1.0D, 1.0D, 1.0D, 1.0D)),
                EPSILON
        );
    }

    @Test
    void missAndBeyondMaximumDistanceReturnNull() {
        PlayerInteractionRayTrace ray = assertValidRay(4.0D);

        assertNull(ray.sphereEntryDistance(new Vector(3.0D, 2.0D, 0.0D), 1.0D));
        assertNull(ray.sphereEntryDistance(new Vector(6.0D, 0.0D, 0.0D), 1.0D));
        assertNull(ray.aabbEntryDistance(new BoundingBox(2.0D, 2.0D, -1.0D, 3.0D, 3.0D, 1.0D)));
        assertNull(ray.aabbEntryDistance(new BoundingBox(5.0D, -1.0D, -1.0D, 6.0D, 1.0D, 1.0D)));
    }

    @Test
    void invalidDirectionAndMaximumDistanceDoNotCreateRay() {
        Vector origin = new Vector(0.0D, 0.0D, 0.0D);

        assertNull(PlayerInteractionRayTrace.create(origin, new Vector(), 5.0D));
        assertNull(PlayerInteractionRayTrace.create(origin, new Vector(Double.NaN, 0.0D, 0.0D), 5.0D));
        assertNull(PlayerInteractionRayTrace.create(origin, new Vector(1.0D, 0.0D, 0.0D), -1.0D));
        assertNull(PlayerInteractionRayTrace.create(origin, new Vector(1.0D, 0.0D, 0.0D), Double.NaN));
        assertNull(PlayerInteractionRayTrace.create(origin, new Vector(1.0D, 0.0D, 0.0D), Double.POSITIVE_INFINITY));
    }

    private PlayerInteractionRayTrace assertValidRay(double maxDistance) {
        PlayerInteractionRayTrace ray = PlayerInteractionRayTrace.create(
                new Vector(0.0D, 0.0D, 0.0D),
                new Vector(1.0D, 0.0D, 0.0D),
                maxDistance
        );
        assertNotNull(ray);
        return ray;
    }
}
