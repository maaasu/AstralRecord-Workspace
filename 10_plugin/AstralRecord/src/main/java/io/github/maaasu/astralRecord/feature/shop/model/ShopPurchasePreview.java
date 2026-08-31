package io.github.maaasu.astralRecord.feature.shop.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record ShopPurchasePreview(
    int quantity,
    long requiredGold,
    long ownedGold,
    @NotNull List<ShopCostItem> requiredItems,
    @NotNull List<ShopCostItem> missingItems,
    boolean canPurchase,
    @NotNull ShopSpecialPurchaseState specialPurchase
) {
    /**
     * 通常商品の購入previewを生成します。
     *
     * @param quantity 購入口数
     * @param requiredGold 必要Gold
     * @param ownedGold 所持Gold
     * @param requiredItems 必要素材・通貨
     * @param missingItems 不足素材・通貨
     * @param canPurchase 購入可否
     */
    public ShopPurchasePreview(
        int quantity,
        long requiredGold,
        long ownedGold,
        @NotNull List<ShopCostItem> requiredItems,
        @NotNull List<ShopCostItem> missingItems,
        boolean canPurchase
    ) {
        this(
            quantity,
            requiredGold,
            ownedGold,
            requiredItems,
            missingItems,
            canPurchase,
            ShopSpecialPurchaseState.standard()
        );
    }
}
