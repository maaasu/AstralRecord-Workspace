package io.github.maaasu.astralRecord.feature.gathering.repository;

import io.github.maaasu.astralRecord.feature.gathering.model.GatheringDefinition;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropItem;
import io.github.maaasu.astralRecord.feature.mob.model.MobMoneyDrop;
import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GatheringDefinitionRepository {
    private static final String RELATIVE_PATH = "42.features.gathering";

    public @NotNull List<GatheringDefinition> findAll() {
        File root = FileDatabaseManager.getInstance().getRootDirectory();
        if (root == null) {
            return List.of();
        }
        File directory = new File(root, RELATIVE_PATH);
        List<File> files = new ArrayList<>();
        collectYamlFiles(directory, files);

        List<GatheringDefinition> result = new ArrayList<>();
        for (File file : files) {
            GatheringDefinition definition = parse(YamlConfiguration.loadConfiguration(file));
            if (definition != null) {
                result.add(definition);
            }
        }
        return result;
    }

    private void collectYamlFiles(@NotNull File directory, @NotNull List<File> result) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectYamlFiles(file, result);
                continue;
            }
            String name = file.getName();
            if (name.endsWith(".yml") && !name.contains("YAML")) {
                result.add(file);
            }
        }
    }

    private @Nullable GatheringDefinition parse(@NotNull YamlConfiguration yaml) {
        String id = yaml.getString("id");
        if (id == null || id.isBlank()) {
            return null;
        }

        Material displayBlock = resolveMaterial(yaml.getString("displayBlock"), Material.STONE);
        return new GatheringDefinition(
                yaml.getInt("schemaVersion", 1),
                id,
                yaml.getString("category", "MINING"),
                yaml.getString("name", id),
                yaml.getInt("maxHealth", 1),
                displayBlock.isBlock() ? displayBlock : Material.STONE,
                new Vector3f(
                        (float) yaml.getDouble("displayScale.x", 1.0D),
                        (float) yaml.getDouble("displayScale.y", 1.0D),
                        (float) yaml.getDouble("displayScale.z", 1.0D)
                ),
                yaml.getStringList("requiredToolTags").stream()
                        .map(value -> value.trim().toUpperCase(Locale.ROOT))
                        .filter(value -> !value.isBlank())
                        .toList(),
                parseDrops(yaml)
        );
    }

    private @NotNull MobDropConfig parseDrops(@NotNull YamlConfiguration yaml) {
        int exp = yaml.getInt("drops.exp", 0);
        MobMoneyDrop money = null;
        if (yaml.contains("drops.money")) {
            money = new MobMoneyDrop(
                    yaml.getInt("drops.money.min", 0),
                    yaml.getInt("drops.money.max", 0)
            );
        }

        List<MobDropItem> items = new ArrayList<>();
        for (var map : yaml.getMapList("drops.items")) {
            Object rawItemId = map.get("itemId");
            if (rawItemId == null) {
                continue;
            }
            String itemId = stripPrefix(rawItemId.toString());
            if (itemId.isBlank()) {
                continue;
            }
            items.add(new MobDropItem(
                    itemId,
                    doubleValue(map.get("rate"), 0.0D),
                    stringValue(map.get("amount"), "1"),
                    booleanValue(map.get("luckAffected"), true),
                    booleanValue(map.get("hidden"), false)
            ));
        }
        return new MobDropConfig(exp, money, items, stripPrefix(yaml.getString("drops.lootTable")));
    }

    private @NotNull Material resolveMaterial(@Nullable String raw, @NotNull Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Material.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private @NotNull String stripPrefix(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        int index = raw.indexOf(':');
        return (index < 0 ? raw : raw.substring(index + 1)).trim();
    }

    private double doubleValue(@Nullable Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean booleanValue(@Nullable Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private @NotNull String stringValue(@Nullable Object value, @NotNull String fallback) {
        return value == null ? fallback : value.toString();
    }
}
