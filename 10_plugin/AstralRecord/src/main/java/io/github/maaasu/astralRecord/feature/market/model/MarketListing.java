package io.github.maaasu.astralRecord.feature.market.model;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record MarketListing(
    UUID listingId,
    UUID sellerAccountId,
    String sellerAccountName,
    @Nullable UUID buyerAccountId,
    @Nullable UUID sourceInventoryEntryId,
    String itemCategory,
    String itemId,
    @Nullable String instanceType,
    @Nullable UUID instanceId,
    long quantity,
    String currencyId,
    long unitPrice,
    long totalPrice,
    long priceFloor,
    @Nullable Long referenceUnitPrice,
    @Nullable Double priceDeviationRate,
    String priceConfidence,
    @Nullable String valuationSignature,
    @Nullable String valuationSnapshotJson,
    String status,
    @Nullable String statusReason,
    Instant listedAt,
    Instant expiresAt,
    @Nullable Instant soldAt,
    @Nullable Instant canceledAt,
    int version,
    Instant createdAt,
    Instant updatedAt
) {
}
