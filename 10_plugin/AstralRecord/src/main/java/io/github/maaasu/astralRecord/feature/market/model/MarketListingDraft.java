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
    private final long minimumUnitPrice;
    private long quantity;
    private long unitPrice;
    private @Nullable MarketPriceQuote priceQuote;

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
        this.minimumUnitPrice = Math.max(1L, unitPrice);
        this.quantity = 1L;
        this.unitPrice = minimumUnitPrice;
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

    /** @return 売値超の単価で合計価格を表現できる最大出品数量 */
    public long maxQuantity() {
        return Math.min(maxQuantity, Long.MAX_VALUE / minimumUnitPrice());
    }

    public long quantity() {
        return quantity;
    }

    /**
     * 出品数量を所持数と価格積の安全範囲へ補正します。
     *
     * @param quantity 希望する出品数量
     */
    public void setQuantity(long quantity) {
        this.quantity = Math.max(1L, Math.min(quantity, maxQuantity()));
    }

    /** @return 売値を上回る最低出品単価。API 見積取得後は API の売値も反映します。 */
    public long minimumUnitPrice() {
        long quotedSellPrice = priceQuote == null ? 0L : priceQuote.sellPrice();
        return Math.max(minimumUnitPrice, quotedSellPrice >= Long.MAX_VALUE
            ? Long.MAX_VALUE : quotedSellPrice + 1L);
    }

    /** @return 数量と long の積があふれない最大単価 */
    public long maximumUnitPrice() {
        return Long.MAX_VALUE / quantity;
    }

    /** @return 相場のおすすめ単価。見積取得失敗時は最低出品単価 */
    public long recommendedUnitPrice() {
        if (priceQuote == null) {
            return minimumUnitPrice();
        }
        Long reference = priceQuote.referenceUnitPrice();
        long candidate = reference == null ? priceQuote.suggestedUnitPrice() : reference;
        return Math.min(maximumUnitPrice(), Math.max(minimumUnitPrice(), candidate));
    }

    /** @return 相場参考上限に合わせた「最大」ボタンの設定単価 */
    public long quickMaxUnitPrice() {
        return priceQuote == null
            ? Math.min(maximumUnitPrice(), minimumUnitPrice())
            : Math.min(maximumUnitPrice(), Math.max(minimumUnitPrice(), priceQuote.allowedMaxUnitPrice()));
    }

    public long unitPrice() {
        return unitPrice;
    }

    /**
     * 単価を数量積の安全上限へ補正します。売値超の判定は確定時に行います。
     *
     * @param unitPrice 希望する単価
     */
    public void setUnitPrice(long unitPrice) {
        this.unitPrice = Math.max(1L, Math.min(unitPrice, Long.MAX_VALUE / quantity));
    }

    /** @return API から取得した相場見積。取得できなければ null */
    public @Nullable MarketPriceQuote priceQuote() {
        return priceQuote;
    }

    /**
     * 出品アイテムの見積を保存します。
     *
     * @param priceQuote API の見積。取得できなければ null
     */
    public void setPriceQuote(@Nullable MarketPriceQuote priceQuote) {
        this.priceQuote = priceQuote;
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
