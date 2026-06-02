package io.github.maaasu.astralRecord.feature.shop.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record ShopRecipeCost(
    @NotNull String recipeId,
    int requiredCurrency,
    @NotNull List<ShopCostItem> ingredients
) {
}
