package io.github.maaasu.astralRecord.shared.challenge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengeParticipationRegistryTest {
    /**
     * Dungeon と Boss が同一参加者を同時受付した場合、共有予約を取得できる挑戦は一つだけとする。
     */
    @Test
    void reservesAParticipantForOnlyOneConcurrentChallenge() throws Exception {
        ChallengeParticipationRegistry registry = new ChallengeParticipationRegistry();
        UUID participantId = UUID.randomUUID();
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ChallengeParticipationRegistry.ReservationResult> first = executor.submit(() -> {
                barrier.await();
                return registry.reserve(firstOwner, "party:first", List.of(participantId), "Dungeon A");
            });
            Future<ChallengeParticipationRegistry.ReservationResult> second = executor.submit(() -> {
                barrier.await();
                return registry.reserve(secondOwner, "party:second", List.of(participantId), "Boss B");
            });

            boolean firstAcquired = first.get().acquired();
            boolean secondAcquired = second.get().acquired();
            assertEquals(1, (firstAcquired ? 1 : 0) + (secondAcquired ? 1 : 0));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 予約競合時は競合元の表示名を返し、解放後は別挑戦が取得できる。
     */
    @Test
    void reportsConflictNameAndReleasesAllIndexes() {
        ChallengeParticipationRegistry registry = new ChallengeParticipationRegistry();
        UUID participantId = UUID.randomUUID();
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();

        assertTrue(registry.reserve(firstOwner, "party:first", List.of(participantId), "Dungeon A").acquired());
        ChallengeParticipationRegistry.ReservationResult conflict = registry.reserve(
                secondOwner, "party:second", List.of(participantId), "Boss B");
        assertFalse(conflict.acquired());
        assertEquals("Dungeon A", conflict.conflictingDisplayName());

        registry.release(firstOwner);

        assertTrue(registry.reserve(secondOwner, "party:second", List.of(participantId), "Boss B").acquired());
        assertTrue(registry.contains(secondOwner));
    }

    /**
     * 待機中にHub外へ離脱した参加者を予約から外した後、その参加者は別挑戦へ参加できる。
     */
    @Test
    void removesAWaitingParticipantWithoutLeavingAStaleReservation() {
        ChallengeParticipationRegistry registry = new ChallengeParticipationRegistry();
        UUID remainingPlayer = UUID.randomUUID();
        UUID departedPlayer = UUID.randomUUID();
        UUID waitingOwner = UUID.randomUUID();
        UUID nextOwner = UUID.randomUUID();

        assertTrue(registry.reserve(
                waitingOwner,
                "party:waiting",
                List.of(remainingPlayer, departedPlayer),
                "Dungeon A"
        ).acquired());
        assertTrue(registry.removeParticipant(waitingOwner, departedPlayer));
        assertFalse(registry.reserve(
                nextOwner,
                "party:next",
                List.of(remainingPlayer),
                "Boss B"
        ).acquired());
        assertTrue(registry.reserve(
                nextOwner,
                "party:next",
                List.of(departedPlayer),
                "Boss B"
        ).acquired());
    }
}
