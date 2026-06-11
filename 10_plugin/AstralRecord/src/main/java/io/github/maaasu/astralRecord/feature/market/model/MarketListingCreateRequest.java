package io.github.maaasu.astralRecord.feature.market.model;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record MarketListingCreateRequest(
    UUID sellerAccountId,
    @Nullable UUID sourceInventoryEntryId,
    String itemCategory,
    String itemId,
    @Nullable String instanceType,
    @Nullable UUID instanceId,
    long quantity,
    String currencyId,
    long unitPrice,
    @Nullable Instant expiresAt,
    UUID createdBy
) {
}
