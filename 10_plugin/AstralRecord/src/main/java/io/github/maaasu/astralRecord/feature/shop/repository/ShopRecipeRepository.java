package io.github.maaasu.astralRecord.feature.shop.repository;

import io.github.maaasu.astralRecord.feature.shop.model.ShopCostItem;
import io.github.maaasu.astralRecord.feature.shop.model.ShopRecipeCost;
import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ShopRecipeRepository {
    private static final String RELATIVE_PATH = "85.shared.recipe";

    public @Nullable ShopRecipeCost findShopRecipeById(@NotNull String recipeId) {
        File root = FileDatabaseManager.getInstance().getRootDirectory();
        if (root == null) {
            return null;
        }
        File directory = new File(root, RELATIVE_PATH);
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return null;
        }
        for (File file : files) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            if (!recipeId.equals(yaml.getString("id"))) {
                continue;
            }
            String category = yaml.getString("category", "");
            if (!"SHOP".equalsIgnoreCase(category)) {
                return null;
            }
            return new ShopRecipeCost(
                recipeId,
                yaml.getInt("requiredCurrency", 0),
                parseIngredients(yaml)
            );
        }
        return null;
    }

    private @NotNull List<ShopCostItem> parseIngredients(@NotNull YamlConfiguration yaml) {
        List<ShopCostItem> costs = new ArrayList<>();
        for (var map : yaml.getMapList("ingredients")) {
            String itemId = stripPrefix(readReference(map.get("itemId")));
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            costs.add(new ShopCostItem(itemId, "material", parseInt(map.get("amount"), 1)));
        }
        return costs;
    }

    private @Nullable String readReference(@Nullable Object raw) {
        if (raw instanceof ConfigurationSection section) {
            return section.getString("ref");
        }
        if (raw instanceof java.util.Map<?, ?> map) {
            Object ref = map.get("ref");
            return ref == null ? null : ref.toString();
        }
        return raw == null ? null : raw.toString();
    }

    private @Nullable String stripPrefix(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        int index = trimmed.indexOf(':');
        return index < 0 ? trimmed : trimmed.substring(index + 1).trim();
    }

    private int parseInt(@Nullable Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return raw == null ? fallback : Integer.parseInt(raw.toString().trim().toUpperCase(Locale.ROOT));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
