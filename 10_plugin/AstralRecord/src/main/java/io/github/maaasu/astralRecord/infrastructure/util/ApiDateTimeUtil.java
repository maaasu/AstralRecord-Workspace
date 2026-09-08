package io.github.maaasu.astralRecord.infrastructure.util;

import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** API が返す ISO-8601 日時を Plugin の日時表現へ変換します。 */
public final class ApiDateTimeUtil {
    private ApiDateTimeUtil() {
        // utility class
    }

    /**
     * offset なしの日時と、UTC の {@code Z} を含む offset 付き日時を受け入れます。
     * offset 付き日時は SQL Server の UTC 値と同じ時刻へ比較できるよう UTC に正規化します。
     *
     * @param value API 応答の ISO-8601 日時
     * @return offset なし入力はその日時、offset 付き入力は UTC に正規化した日時
     */
    public static @NotNull LocalDateTime parseLocalDateTime(@NotNull String value) {
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException localFailure) {
            try {
                return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();
            } catch (DateTimeParseException offsetFailure) {
                offsetFailure.addSuppressed(localFailure);
                throw offsetFailure;
            }
        }
    }
}
