package io.github.maaasu.astralRecord.feature.trade.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/** API へ送信するトレード確定要求です。 */
public record TradeCommitRequest(
    @NotNull UUID operationId,
    @NotNull UUID playerAAccountId,
    @NotNull UUID playerBAccountId,
    @NotNull List<TradeCommitItem> playerAItems,
    @NotNull List<TradeCommitItem> playerBItems,
    long playerAGold,
    long playerBGold,
    @NotNull UUID updatedBy
) {
}
