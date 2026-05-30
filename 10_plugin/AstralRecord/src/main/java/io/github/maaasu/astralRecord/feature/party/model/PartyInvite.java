package io.github.maaasu.astralRecord.feature.party.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * メモリ上だけで保持するパーティー招待です。
 */
public record PartyInvite(
    @NotNull UUID partyId,
    @NotNull UUID leaderId,
    @NotNull UUID targetId,
    @NotNull Instant createdAt
) {
}
