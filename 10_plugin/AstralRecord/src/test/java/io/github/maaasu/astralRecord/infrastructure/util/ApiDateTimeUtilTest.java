package io.github.maaasu.astralRecord.infrastructure.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiDateTimeUtilTest {
    /**
     * API日時はoffsetなし、Z、任意offsetを受理し、offset付き値はUTCへ正規化する。
     */
    @Test
    void acceptsSupportedApiDateTimesAndNormalizesOffsetsToUtc() {
        LocalDateTime expected = LocalDateTime.of(2026, 9, 8, 8, 12, 53, 537_000_000);

        assertEquals(expected, ApiDateTimeUtil.parseLocalDateTime("2026-09-08T08:12:53.537"));
        assertEquals(expected, ApiDateTimeUtil.parseLocalDateTime("2026-09-08T08:12:53.537Z"));
        assertEquals(expected, ApiDateTimeUtil.parseLocalDateTime("2026-09-08T17:12:53.537+09:00"));
    }
}
