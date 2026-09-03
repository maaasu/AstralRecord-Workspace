package io.github.maaasu.astralRecord.shared.challenge;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengeWaitingHubArrivalGuardTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_0-概要.md
     * 章・見出し: # 32_0-概要 > ## 4. 主要境界と不変条件
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_0-概要.md
     * 章・見出し: # 26_0-概要 > ## 2. 対象範囲
     * 検証契約: 挑戦待機Hub到着直後の初期スポーン離脱入力は約2秒間だけ抑止し、境界到達後は再び許可する。
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
