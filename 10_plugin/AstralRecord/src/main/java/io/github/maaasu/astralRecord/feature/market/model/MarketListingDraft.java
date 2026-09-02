package io.github.maaasu.astralRecord.feature.market.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * マーケット出品 GUI で編集中の、まだ API へ確定していない出品内容です。
 */
public final class MarketListingDraft {
    private final UUID contextId;
    private final List<MarketListingSource> sourceEntries;
    private final String itemCategory;
    private final String itemId;
    private final @Nullable String instanceType;
    private final @Nullable UUID instanceId;
    private final long maxQuantity;
    private long quantity;
    private long unitPrice;

    public MarketListingDraft(
        @NotNull UUID contextId,
        @NotNull List<MarketListingSource> sourceEntries,
        @NotNull String itemCategory,
        @NotNull String itemId,
        @Nullable String instanceType,
        @Nullable UUID instanceId,
        long maxQuantity,
        long unitPrice
    ) {
        this.contextId = contextId;
        this.sourceEntries = List.copyOf(sourceEntries);
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

    public @NotNull List<MarketListingSource> sourceEntries() {
        return sourceEntries;
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

    /**
     * 現在の出品予定数量を、保持している source entry の順に割り当てた escrow 要求を返します。
     *
     * @return API へ送信する entry ごとの確保数量
     */
    public @NotNull List<MarketListingSource> selectedSources() {
        return selectedSources(sourceEntries);
    }

    /**
     * 現在の出品予定数量を、指定された最新の source entry 順に割り当てた escrow 要求を返します。
     * <p>
     * 出品設定を開いてから所持品が変化する可能性があるため、出品確定時は保存済み候補ではなく
     * 保存 lane 内で再取得した通常アイテム共通消費順の候補を渡します。
     *
     * @param availableSources 出品確定時点の source entry 候補
     * @return API へ送信する entry ごとの確保数量
     * @throws IllegalStateException 指定候補が出品予定数量を満たさない場合
     */
    public @NotNull List<MarketListingSource> selectedSources(
        @NotNull List<MarketListingSource> availableSources
    ) {
        long remaining = quantity;
        List<MarketListingSource> selected = new ArrayList<>();
        for (MarketListingSource source : availableSources) {
            if (remaining <= 0L) {
                break;
            }
            long selectedQuantity = Math.min(source.quantity(), remaining);
            selected.add(new MarketListingSource(source.inventoryEntryId(), selectedQuantity));
            remaining -= selectedQuantity;
        }
        if (remaining > 0L) {
            throw new IllegalStateException("Selected sources do not cover draft quantity");
        }
        return List.copyOf(selected);
    }
}
