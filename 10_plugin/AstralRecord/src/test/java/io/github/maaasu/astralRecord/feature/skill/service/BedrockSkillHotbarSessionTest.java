package io.github.maaasu.astralRecord.feature.skill.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockSkillHotbarSessionTest {
    @Test
    void cooldownUsesWholeSecondsRoundedUpAndCapsAtOneStack() {
        assertEquals(0, BedrockSkillHotbarSession.cooldownCount(0));
        assertEquals(1, BedrockSkillHotbarSession.cooldownCount(1));
        assertEquals(1, BedrockSkillHotbarSession.cooldownCount(20));
        assertEquals(2, BedrockSkillHotbarSession.cooldownCount(21));
        assertEquals(63, BedrockSkillHotbarSession.cooldownCount(63 * 20));
        assertEquals(64, BedrockSkillHotbarSession.cooldownCount(64 * 20));
        assertEquals(64, BedrockSkillHotbarSession.cooldownCount(Long.MAX_VALUE));
    }

    @Test
    void clickGateRejectsSameTickAndQueuedEarlierClicks() {
        assertFalse(BedrockSkillHotbarSession.isClickCoolingDown(null, 10));
        assertTrue(BedrockSkillHotbarSession.isClickCoolingDown(10, 9));
        assertTrue(BedrockSkillHotbarSession.isClickCoolingDown(10, 10));
        assertTrue(BedrockSkillHotbarSession.isClickCoolingDown(10, 11));
        assertTrue(BedrockSkillHotbarSession.isClickCoolingDown(10, 12));
        assertFalse(BedrockSkillHotbarSession.isClickCoolingDown(10, 13));
        assertTrue(BedrockSkillHotbarSession.isClickCoolingDown(Integer.MAX_VALUE, Integer.MIN_VALUE));
    }

    @Test
    void heldSlotIsAppliedWhenItsPacketIsHandledInQueueOrder() {
        var inputQueue = new ArrayDeque<Runnable>();
        var selectedSlot = new AtomicInteger(8);
        var casts = new ArrayList<Integer>();
        var actualChanges = new ArrayList<Integer>();

        inputQueue.addLast(heldSlotPacket(0, selectedSlot, actualChanges));
        inputQueue.addLast(() -> casts.add(selectedSlot.get()));
        inputQueue.addLast(heldSlotPacket(7, selectedSlot, actualChanges));
        assertEquals(8, selectedSlot.get());
        while (!inputQueue.isEmpty()) {
            inputQueue.removeFirst().run();
        }

        assertEquals(List.of(0), casts);
        assertEquals(7, selectedSlot.get());
        assertTrue(actualChanges.isEmpty());

    }

    private static Runnable heldSlotPacket(int slot, AtomicInteger selectedSlot, List<Integer> actualChanges) {
        TestPacket original = () -> actualChanges.add(slot);
        TestPacket wrapped = (TestPacket) BedrockSkillHotbarPacketQueue.routePacket(
            TestPacket.class, original, () -> selectedSlot.set(slot));
        return wrapped::handle;
    }

    public interface TestPacket {
        void handle();
    }
}
