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
    public ShopPurchasePreview(
        int quantity,
        long requiredGold,
        long ownedGold,
        @NotNull List<ShopCostItem> requiredItems,
        @NotNull List<ShopCostItem> missingItems,
        boolean canPurchase
    ) {
        this(quantity, requiredGold, ownedGold, requiredItems, missingItems, canPurchase, ShopSpecialPurchaseState.standard());
    }
}
