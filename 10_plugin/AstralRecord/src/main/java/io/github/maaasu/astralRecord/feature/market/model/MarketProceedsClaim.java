package io.github.maaasu.astralRecord.feature.market.model;

import java.util.List;
import java.util.UUID;

/** 売上受取 API が確定した金額と、再同期が必要な通貨 entry を表します。 */
public record MarketProceedsClaim(
    UUID listingId,
    long amount,
    List<UUID> affectedInventoryEntryIds
) {
}
