package io.github.maaasu.astralRecord.feature.market.model;

import java.util.UUID;

/** Webから保留された本人の購入要求です。 */
public record MarketWebPurchase(
    UUID operationId,
    UUID listingId,
    UUID buyerAccountId,
    long quantity
) {
}
