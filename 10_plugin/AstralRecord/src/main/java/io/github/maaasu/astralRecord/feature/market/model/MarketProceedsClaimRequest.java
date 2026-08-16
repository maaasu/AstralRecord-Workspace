package io.github.maaasu.astralRecord.feature.market.model;

import java.util.UUID;

/**
 * 売却済み出品の売上を受け取るための API リクエストです。
 * 同一出品を再送するときは同じ {@code idempotencyKey} を指定します。
 */
public record MarketProceedsClaimRequest(
    UUID sellerAccountId,
    String idempotencyKey,
    UUID updatedBy
) {
}
