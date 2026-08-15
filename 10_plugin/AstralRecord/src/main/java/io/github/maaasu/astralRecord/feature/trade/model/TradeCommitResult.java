package io.github.maaasu.astralRecord.feature.trade.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API で確定済みのトレードと、各参加者の再同期対象 entry です。 */
public record TradeCommitResult(
    @NotNull UUID operationId,
    @NotNull List<UUID> playerAAffectedInventoryEntryIds,
    @NotNull List<UUID> playerBAffectedInventoryEntryIds,
    @NotNull Instant completedAt
) {
}
