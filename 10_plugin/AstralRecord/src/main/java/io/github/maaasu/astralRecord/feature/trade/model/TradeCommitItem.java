package io.github.maaasu.astralRecord.feature.trade.model;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** API のトレード確定へ渡す提示元 inventory entry と数量です。 */
public record TradeCommitItem(@NotNull UUID sourceInventoryEntryId, long quantity) {
}
