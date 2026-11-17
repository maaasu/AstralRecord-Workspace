package io.github.maaasu.astralRecord.shared.challenge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengeDeathPolicyTest {
    /**
     * 設定回数までは許容し、上限0を含めて次の死亡で終了する。
     */
    @Test
    void endsOnlyAfterTheDeathLimitIsExceeded() {
        assertAll(
            () -> assertDeathLimitBoundary(0),
            () -> assertDeathLimitBoundary(2)
        );
    }

    private static void assertDeathLimitBoundary(int limit) {
        assertFalse(ChallengeDeathPolicy.isExceeded(limit, limit), () -> "死亡上限: " + limit);
        assertTrue(ChallengeDeathPolicy.isExceeded(limit + 1, limit), () -> "死亡上限: " + limit);
    }
}
