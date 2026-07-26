package io.github.maaasu.astralRecord.feature.skill.active.service;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillTargetingServiceTest {

    private static final BoundingBox STANDARD_MOB = new BoundingBox(
            -0.3D, 0.0D, 4.7D,
            0.3D, 1.8D, 5.3D
    );

    @Test
    void horizontalEyeRayHitsTheMobsActualBodyBounds() {
        assertTrue(SkillTargetingService.intersectsLine(
                STANDARD_MOB,
                new Vector(0.0D, 1.62D, 0.0D),
                new Vector(0.0D, 0.0D, 1.0D),
                10.0D,
                0.45D
        ));
    }

    @Test
    void capsuleRadiusDoesNotHitTargetsOutsideItsExpandedBounds() {
        assertFalse(SkillTargetingService.intersectsLine(
                STANDARD_MOB,
                new Vector(0.0D, 2.4D, 0.0D),
                new Vector(0.0D, 0.0D, 1.0D),
                10.0D,
                0.45D
        ));
    }

    @Test
    void lineDoesNotHitTargetsBehindItsOrigin() {
        assertFalse(SkillTargetingService.intersectsLine(
                STANDARD_MOB,
                new Vector(0.0D, 1.0D, 10.0D),
                new Vector(0.0D, 0.0D, 1.0D),
                10.0D,
                0.5D
        ));
    }

    @Test
    void intersectionDistanceOrdersByFrontFaceInsteadOfTargetCenter() {
        BoundingBox largeFrontTarget = new BoundingBox(-1.0D, 0.0D, 2.0D, 1.0D, 3.0D, 10.0D);
        BoundingBox smallRearTarget = new BoundingBox(-0.3D, 0.0D, 4.0D, 0.3D, 1.8D, 4.5D);
        Vector origin = new Vector(0.0D, 1.0D, 0.0D);
        Vector direction = new Vector(0.0D, 0.0D, 1.0D);

        double largeDistance = SkillTargetingService.lineIntersectionDistance(
                largeFrontTarget, origin, direction, 20.0D, 0.0D
        );
        double smallDistance = SkillTargetingService.lineIntersectionDistance(
                smallRearTarget, origin, direction, 20.0D, 0.0D
        );

        assertEquals(2.0D, largeDistance, 1.0E-9D);
        assertEquals(4.0D, smallDistance, 1.0E-9D);
        assertTrue(largeDistance < smallDistance);
    }
}
