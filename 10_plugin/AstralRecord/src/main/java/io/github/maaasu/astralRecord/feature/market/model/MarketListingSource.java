package io.github.maaasu.astralRecord.feature.market.model;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

/**
 * マーケット出品で escrow 化する所持品 entry と、その entry から確保する数量です。
 * スタック品は複数 entry をまとめて 1 出品にでき、個体品は常に 1 entry・数量 1 になります。
 */
public record MarketListingSource(@NotNull UUID inventoryEntryId, long quantity) {
    public MarketListingSource {
        Objects.requireNonNull(inventoryEntryId, "inventoryEntryId");
        if (quantity < 1L) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
