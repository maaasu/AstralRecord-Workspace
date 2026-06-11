package io.github.maaasu.astralRecord.feature.market.model;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record MarketTransaction(
    UUID transactionId,
    UUID listingId,
    UUID sellerAccountId,
    UUID buyerAccountId,
    String itemCategory,
    String itemId,
    @Nullable String instanceType,
    @Nullable UUID instanceId,
    long quantity,
    String currencyId,
    long unitPrice,
    long totalPrice,
    long feeAmount,
    long sellerProceeds,
    Instant completedAt
) {
}
