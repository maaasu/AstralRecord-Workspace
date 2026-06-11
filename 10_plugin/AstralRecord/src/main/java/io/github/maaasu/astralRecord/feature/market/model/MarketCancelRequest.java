package io.github.maaasu.astralRecord.feature.market.model;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record MarketCancelRequest(
    UUID sellerAccountId,
    @Nullable String reason,
    UUID updatedBy
) {
}
