package io.github.maaasu.astralRecord.feature.mob.spawner.repository;

import io.github.maaasu.astralRecord.feature.mob.spawner.model.MobSpawnerDefinition;
import io.github.maaasu.astralRecord.feature.mob.spawner.model.MobSpawnerEntry;
import io.github.maaasu.astralRecord.feature.mob.spawner.model.MobSpawnerTimeWindow;
import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * filebase の Mob スポナー定義を読み込むリポジトリです。
 */
public class MobSpawnerDefinitionRepository {

    private static final String RELATIVE_PATH = "40.features.mob" + File.separator + "spawner";

    /**
     * すべてのスポナー定義を読み込みます。
     *
     * @return 読み込めたスポナー定義一覧
     */
    @NotNull
    public List<MobSpawnerDefinition> findAll() {
        File root = FileDatabaseManager.getInstance().getRootDirectory();
        if (root == null) {
            return List.of();
        }
        File directory = new File(root, RELATIVE_PATH);
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml") && !name.endsWith("定義.yml"));
        if (files == null || files.length == 0) {
            return List.of();
        }

        List<MobSpawnerDefinition> result = new ArrayList<>();
        for (File file : files) {
            MobSpawnerDefinition definition = parse(YamlConfiguration.loadConfiguration(file));
            if (definition != null) {
                result.add(definition);
            }
        }
        return result;
    }

    @Nullable
    private MobSpawnerDefinition parse(@NotNull YamlConfiguration yaml) {
        String id = yaml.getString("id");
        if (id == null || id.isBlank()) {
            return null;
        }

        return new MobSpawnerDefinition(
                id,
                yaml.getDouble("radiusMeters", 16.0D),
                parseSpawnMobs(yaml),
                parseTimeWindows(yaml),
                resolveMaterial(yaml.getString("itemMaterial")),
                yaml.getLong("spawnIntervalTicks", 100L),
                yaml.getInt("spawnLimit.maxAlivePerSpawner", 8),
                yaml.getInt("spawnLimit.maxNearbyMobs", 18),
                yaml.getInt("spawnLimit.spawnPerPlayer", 1)
        );
    }

    @NotNull
    private List<MobSpawnerEntry> parseSpawnMobs(@NotNull YamlConfiguration yaml) {
        List<MobSpawnerEntry> entries = new ArrayList<>();
        for (var map : yaml.getMapList("spawnMobs")) {
            Object rawMob = map.get("mobId");
            if (rawMob == null) {
                continue;
            }
            String mobId = stripPrefix(rawMob.toString());
            if (mobId.isBlank()) {
                continue;
            }
            int weight = parseInt(map.get("weight"), 1);
            entries.add(new MobSpawnerEntry(mobId, weight));
        }
        return entries;
    }

    @NotNull
    private List<MobSpawnerTimeWindow> parseTimeWindows(@NotNull YamlConfiguration yaml) {
        List<MobSpawnerTimeWindow> windows = new ArrayList<>();
        for (var map : yaml.getMapList("spawnTimes")) {
            long start = parseLong(map.get("startTick"), 0L);
            long end = parseLong(map.get("endTick"), 23999L);
            windows.add(new MobSpawnerTimeWindow(start, end));
        }
        return windows;
    }

    @NotNull
    private Material resolveMaterial(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Material.SPAWNER;
        }
        try {
            Material material = Material.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            return material.isBlock() ? material : Material.SPAWNER;
        } catch (IllegalArgumentException ignored) {
            return Material.SPAWNER;
        }
    }

    @NotNull
    private String stripPrefix(@NotNull String raw) {
        int index = raw.indexOf(':');
        return index < 0 ? raw.trim() : raw.substring(index + 1).trim();
    }

    private int parseInt(@Nullable Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return raw == null ? fallback : Integer.parseInt(raw.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long parseLong(@Nullable Object raw, long fallback) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return raw == null ? fallback : Long.parseLong(raw.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
