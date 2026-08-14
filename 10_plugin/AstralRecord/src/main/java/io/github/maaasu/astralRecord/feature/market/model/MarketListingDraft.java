package io.github.maaasu.astralRecord.feature.market.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * マーケット出品 GUI で編集中の、まだ API へ確定していない出品内容です。
 */
public final class MarketListingDraft {
    private final UUID contextId;
    private final UUID sourceInventoryEntryId;
    private final String itemCategory;
    private final String itemId;
    private final @Nullable String instanceType;
    private final @Nullable UUID instanceId;
    private final long maxQuantity;
    private long quantity;
    private long unitPrice;

    public MarketListingDraft(
        @NotNull UUID contextId,
        @NotNull UUID sourceInventoryEntryId,
        @NotNull String itemCategory,
        @NotNull String itemId,
        @Nullable String instanceType,
        @Nullable UUID instanceId,
        long maxQuantity,
        long unitPrice
    ) {
        this.contextId = contextId;
        this.sourceInventoryEntryId = sourceInventoryEntryId;
        this.itemCategory = itemCategory;
        this.itemId = itemId;
        this.instanceType = instanceType;
        this.instanceId = instanceId;
        this.maxQuantity = Math.max(1L, maxQuantity);
        this.quantity = 1L;
        this.unitPrice = Math.max(1L, unitPrice);
    }

    public @NotNull UUID contextId() {
        return contextId;
    }

    public @NotNull UUID sourceInventoryEntryId() {
        return sourceInventoryEntryId;
    }

    public @NotNull String itemCategory() {
        return itemCategory;
    }

    public @NotNull String itemId() {
        return itemId;
    }

    public @Nullable String instanceType() {
        return instanceType;
    }

    public @Nullable UUID instanceId() {
        return instanceId;
    }

    public long maxQuantity() {
        return maxQuantity;
    }

    public long quantity() {
        return quantity;
    }

    public void setQuantity(long quantity) {
        this.quantity = Math.max(1L, Math.min(quantity, maxQuantity));
    }

    public long unitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(long unitPrice) {
        this.unitPrice = Math.max(1L, Math.min(unitPrice, Long.MAX_VALUE / quantity));
    }

    public long totalPrice() {
        return unitPrice > Long.MAX_VALUE / quantity ? Long.MAX_VALUE : unitPrice * quantity;
    }
}
