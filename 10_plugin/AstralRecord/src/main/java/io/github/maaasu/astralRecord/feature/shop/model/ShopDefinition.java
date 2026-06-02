package io.github.maaasu.astralRecord.feature.shop.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record ShopDefinition(
    @NotNull String id,
    @NotNull String name,
    @NotNull List<ShopEntry> entries
) {
    public ShopEntry findEntry(@NotNull String entryId) {
        return entries.stream()
            .filter(entry -> entry.id().equals(entryId))
            .findFirst()
            .orElse(null);
    }
}
