package io.github.maaasu.astralRecord.feature.market.model;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record MarketAccountSummary(
    UUID accountId,
    int activeListingCount,
    int maxActiveListingCount,
    int usedListingSlotCount,
    int maxListingSlotCount,
    int completedTradeCount,
    String tier,
    @Nullable Instant suspendedUntil,
    Instant updatedAt
) {
}
