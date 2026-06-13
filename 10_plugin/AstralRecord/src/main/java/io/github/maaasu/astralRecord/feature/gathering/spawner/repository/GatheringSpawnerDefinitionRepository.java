package io.github.maaasu.astralRecord.feature.gathering.spawner.repository;

import io.github.maaasu.astralRecord.feature.gathering.spawner.model.GatheringSpawnerDefinition;
import io.github.maaasu.astralRecord.feature.gathering.spawner.model.GatheringSpawnerEntry;
import io.github.maaasu.astralRecord.feature.gathering.spawner.model.GatheringSpawnerTimeWindow;
import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GatheringSpawnerDefinitionRepository {
    private static final String RELATIVE_PATH = "43.features.gathering.spawner";

    public @NotNull List<GatheringSpawnerDefinition> findAll() {
        File root = FileDatabaseManager.getInstance().getRootDirectory();
        if (root == null) {
            return List.of();
        }
        File directory = new File(root, RELATIVE_PATH);
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml") && !name.contains("YAML"));
        if (files == null) {
            return List.of();
        }

        List<GatheringSpawnerDefinition> result = new ArrayList<>();
        for (File file : files) {
            GatheringSpawnerDefinition definition = parse(YamlConfiguration.loadConfiguration(file));
            if (definition != null) {
                result.add(definition);
            }
        }
        return result;
    }

    private @Nullable GatheringSpawnerDefinition parse(@NotNull YamlConfiguration yaml) {
        String id = yaml.getString("id");
        if (id == null || id.isBlank()) {
            return null;
        }

        return new GatheringSpawnerDefinition(
                id,
                yaml.getDouble("radiusMeters", 16.0D),
                parseEntries(yaml),
                parseTimeWindows(yaml),
                resolveMaterial(yaml.getString("itemMaterial"), Material.SPAWNER),
                yaml.getLong("spawnIntervalTicks", 100L),
                yaml.getInt("spawnLimit.maxAlivePerSpawner", 8),
                yaml.getInt("spawnLimit.maxNearbyGatherings", 18),
                yaml.getInt("spawnLimit.spawnPerPlayer", 1),
                yaml.getStringList("requiredBaseBlocks").stream()
                        .map(raw -> resolveMaterial(raw, null))
                        .filter(material -> material != null && material.isBlock())
                        .toList()
        );
    }

    private @NotNull List<GatheringSpawnerEntry> parseEntries(@NotNull YamlConfiguration yaml) {
        List<GatheringSpawnerEntry> entries = new ArrayList<>();
        for (var map : yaml.getMapList("spawnGatherings")) {
            Object rawId = map.get("gatheringId");
            if (rawId == null) {
                continue;
            }
            String gatheringId = stripPrefix(rawId.toString());
            if (gatheringId.isBlank()) {
                continue;
            }
            entries.add(new GatheringSpawnerEntry(gatheringId, intValue(map.get("weight"), 1)));
        }
        return entries;
    }

    private @NotNull List<GatheringSpawnerTimeWindow> parseTimeWindows(@NotNull YamlConfiguration yaml) {
        List<GatheringSpawnerTimeWindow> windows = new ArrayList<>();
        for (var map : yaml.getMapList("spawnTimes")) {
            windows.add(new GatheringSpawnerTimeWindow(
                    longValue(map.get("startTick"), 0L),
                    longValue(map.get("endTick"), 23999L)
            ));
        }
        return windows;
    }

    private @Nullable Material resolveMaterial(@Nullable String raw, @Nullable Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Material.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private @NotNull String stripPrefix(@NotNull String raw) {
        int index = raw.indexOf(':');
        return (index < 0 ? raw : raw.substring(index + 1)).trim();
    }

    private int intValue(@Nullable Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long longValue(@Nullable Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
