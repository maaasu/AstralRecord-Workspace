package io.github.maaasu.astralRecord.feature.market.model;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record MarketPriceQuoteRequest(
    @Nullable UUID accountId,
    String itemCategory,
    String itemId,
    @Nullable String instanceType,
    @Nullable UUID instanceId,
    long quantity,
    @Nullable Long unitPrice
) {
}
