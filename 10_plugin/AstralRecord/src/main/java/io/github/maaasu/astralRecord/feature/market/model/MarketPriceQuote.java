package io.github.maaasu.astralRecord.feature.market.model;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record MarketPriceQuote(
    String itemCategory,
    String itemId,
    @Nullable String instanceType,
    @Nullable UUID instanceId,
    long sellPrice,
    long suggestedUnitPrice,
    @Nullable Long referenceUnitPrice,
    int sampleCount,
    String referenceScope,
    String confidence,
    long allowedMinUnitPrice,
    long allowedMaxUnitPrice,
    String judgement,
    @Nullable String valuationSignature,
    @Nullable Double rollQualityScore,
    @Nullable String rollQualityBucket,
    Instant evaluatedAt
) {
}
