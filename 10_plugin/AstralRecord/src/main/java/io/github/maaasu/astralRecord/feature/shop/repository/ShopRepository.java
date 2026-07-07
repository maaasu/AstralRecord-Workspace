package io.github.maaasu.astralRecord.feature.shop.repository;

import io.github.maaasu.astralRecord.feature.shop.model.ShopCostItem;
import io.github.maaasu.astralRecord.feature.shop.model.ShopDefinition;
import io.github.maaasu.astralRecord.feature.shop.model.ShopEntry;
import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class ShopRepository {
    private static final String RELATIVE_PATH = "45.features.shop";

    public @NotNull List<ShopDefinition> findAll() {
        File root = FileDatabaseManager.getInstance().getRootDirectory();
        if (root == null) {
            return List.of();
        }
        File directory = new File(root, RELATIVE_PATH);
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            return List.of();
        }
        List<ShopDefinition> result = new ArrayList<>();
        for (File file : files) {
            ShopDefinition shop = parse(YamlConfiguration.loadConfiguration(file));
            if (shop != null) {
                result.add(shop);
            }
        }
        return result;
    }

    public @Nullable ShopDefinition findById(@NotNull String shopId) {
        return findAll().stream()
            .filter(shop -> shop.id().equals(shopId))
            .findFirst()
            .orElse(null);
    }

    private @Nullable ShopDefinition parse(@NotNull YamlConfiguration yaml) {
        String id = yaml.getString("id");
        if (id == null || id.isBlank()) {
            return null;
        }
        return new ShopDefinition(id, yaml.getString("name", id), parseEntries(yaml));
    }

    private @NotNull List<ShopEntry> parseEntries(@NotNull YamlConfiguration yaml) {
        List<ShopEntry> entries = new ArrayList<>();
        for (var map : yaml.getMapList("items")) {
            String id = asString(map.get("id"));
            String itemId = stripPrefix(readReference(map.get("itemId")));
            if (id == null || id.isBlank() || itemId == null || itemId.isBlank()) {
                continue;
            }
            entries.add(new ShopEntry(
                id,
                itemId,
                valueOrDefault(asString(map.get("category")), "material"),
                parseInt(map.get("amount"), 1),
                Math.max(1, parseInt(map.get("page"), 1)),
                parseOptionalInt(map.get("slot")),
                parseOptionalInt(map.get("row")),
                parseOptionalInt(map.get("column")),
                parseInt(map.get("priceGold"), 0),
                parseRequiredItems(map),
                stripPrefix(readReference(map.get("recipeId")))
            ));
        }
        return entries;
    }

    private @NotNull List<ShopCostItem> parseRequiredItems(@NotNull java.util.Map<?, ?> entryMap) {
        Object rawItems = entryMap.get("requiredItems");
        if (!(rawItems instanceof List<?> list)) {
            return List.of();
        }
        List<ShopCostItem> result = new ArrayList<>();
        for (Object raw : list) {
            if (!(raw instanceof java.util.Map<?, ?> map)) {
                continue;
            }
            String itemId = stripPrefix(readReference(map.get("itemId")));
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            result.add(new ShopCostItem(
                itemId,
                valueOrDefault(asString(map.get("category")), "material"),
                parseInt(map.get("amount"), 1)
            ));
        }
        return result;
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

    private @Nullable String asString(@Nullable Object raw) {
        return raw == null ? null : raw.toString();
    }

    private @NotNull String valueOrDefault(@Nullable String value, @NotNull String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private @Nullable Integer parseOptionalInt(@Nullable Object raw) {
        if (raw == null) {
            return null;
        }
        return parseInt(raw, 0);
    }

    private int parseInt(@Nullable Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return raw == null ? fallback : Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
