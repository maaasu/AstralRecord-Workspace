package io.github.maaasu.astralRecord.shared.challenge;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengeWaitingHubArrivalGuardTest {
    /**
     * 挑戦待機Hub到着直後の初期スポーン離脱入力は約2秒間だけ抑止し、境界到達後は再び許可する。
     */
    @Test
    void suppressesInitialSpawnLeaveForTwoSecondsAfterHubArrival() {
        ChallengeWaitingHubArrivalGuard guard = new ChallengeWaitingHubArrivalGuard();
        UUID playerId = UUID.randomUUID();
        long arrivalNanos = 10_000_000_000L;
        long suppressionNanos = Duration.ofSeconds(2L).toNanos();

        guard.markArrivalAt(playerId, arrivalNanos);

        assertTrue(guard.isSuppressedAt(playerId, arrivalNanos));
        assertTrue(guard.isSuppressedAt(playerId, arrivalNanos + suppressionNanos - 1L));
        assertFalse(guard.isSuppressedAt(playerId, arrivalNanos + suppressionNanos));
        assertFalse(guard.isSuppressedAt(UUID.randomUUID(), arrivalNanos));

        guard.markArrivalAt(playerId, arrivalNanos);
        guard.clear(playerId);
        assertFalse(guard.isSuppressedAt(playerId, arrivalNanos));
    }
}
