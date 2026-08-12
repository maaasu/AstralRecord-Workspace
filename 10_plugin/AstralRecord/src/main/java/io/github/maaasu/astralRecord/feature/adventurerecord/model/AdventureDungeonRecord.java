package io.github.maaasu.astralRecord.feature.adventurerecord.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/** アカウント単位で永続化されたダンジョン踏破記録です。 */
public record AdventureDungeonRecord(
        @NotNull UUID accountDungeonRecordId,
        @NotNull UUID accountId,
        @NotNull String dungeonId,
        long clearCount,
        @NotNull Instant firstClearedAt,
        @NotNull Instant lastClearedAt
) {
    public AdventureDungeonRecord {
        dungeonId = dungeonId.trim();
    }
}
