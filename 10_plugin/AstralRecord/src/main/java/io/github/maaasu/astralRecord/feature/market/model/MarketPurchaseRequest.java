package io.github.maaasu.astralRecord.feature.market.model;

import java.util.UUID;

public record MarketPurchaseRequest(
    UUID buyerAccountId,
    String idempotencyKey,
    UUID updatedBy
) {
}
