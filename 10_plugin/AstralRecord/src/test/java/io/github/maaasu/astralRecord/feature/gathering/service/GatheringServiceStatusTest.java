package io.github.maaasu.astralRecord.feature.gathering.service;

import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatheringServiceStatusTest {

    @Test
    void missingMiningSpeedUsesMinimumDamage() {
        assertEquals(1, GatheringService.resolveMiningDamage((StatusValue) null));
    }

    @Test
    void fixedMiningSpeedBecomesGatheringDamage() {
        assertEquals(10, GatheringService.resolveMiningDamage(new StatusValue(10.0D, 0.0D)));
    }

    @Test
    void rangedMiningSpeedRollsDamageWithinBounds() {
        StatusValue value = new StatusValue(0.0D, 0.0D, 2.0D, 10.0D);

        for (int i = 0; i < 1_000; i++) {
            int damage = GatheringService.resolveMiningDamage(value);
            assertTrue(damage >= 2 && damage <= 10);
        }
    }
}
