package io.github.maaasu.astralRecord.feature.shop.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record ShopEntry(
    @NotNull String id,
    @NotNull String itemId,
    @NotNull String category,
    int amount,
    int page,
    @Nullable Integer slot,
    @Nullable Integer row,
    @Nullable Integer column,
    int priceGold,
    @NotNull List<ShopCostItem> requiredItems,
    @Nullable String recipeId
) {
}
