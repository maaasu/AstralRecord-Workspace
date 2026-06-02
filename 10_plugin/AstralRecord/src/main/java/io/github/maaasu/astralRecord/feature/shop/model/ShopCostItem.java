package io.github.maaasu.astralRecord.feature.shop.model;

import org.jetbrains.annotations.NotNull;

public record ShopCostItem(
    @NotNull String itemId,
    @NotNull String category,
    int amount
) {
    public ShopCostItem multiplied(int quantity) {
        return new ShopCostItem(itemId, category, Math.max(0, amount) * Math.max(1, quantity));
    }
}
