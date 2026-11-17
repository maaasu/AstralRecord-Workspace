package io.github.maaasu.astralRecord.feature.boss.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MossZombieBedrockMotionDeliveryTest {
    // Boss presentation: initial idle, one replay, no repeated same-state delivery, then yaw-only updates.
    @Test
    void sendsInitialIdleAndYawChangesEvenWhenAttackPoseDoesNotChange() {
        long beforeWrap = BossBedrockMotion.state(0, false, 359.0f);
        long afterWrap = BossBedrockMotion.state(0, false, 1.0f);
        assertTrue(MossZombiePackDisplay.shouldSendBedrockMotion(null, beforeWrap));
        assertTrue(MossZombiePackDisplay.shouldSendBedrockMotion(
                new MossZombiePackDisplay.BedrockMotionDelivery(beforeWrap, true), beforeWrap));
        var delivered = new MossZombiePackDisplay.BedrockMotionDelivery(beforeWrap, false);
        assertFalse(MossZombiePackDisplay.shouldSendBedrockMotion(delivered, beforeWrap));
        assertTrue(MossZombiePackDisplay.shouldSendBedrockMotion(delivered, afterWrap));
        assertTrue(MossZombiePackDisplay.shouldSendBedrockMotion(delivered, BossBedrockMotion.state(1, false, 359.0f)));
    }
}
