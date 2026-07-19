package io.github.maaasu.astralRecord.feature.gathering.spawner.repository;

import io.github.maaasu.astralRecord.feature.gathering.spawner.model.GatheringSpawnerLocation;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GatheringSpawnerLocationRepository {
    private static final String FILE_NAME = "gathering_spawners.yml";

    private final Plugin plugin;

    public GatheringSpawnerLocationRepository(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    public @NotNull List<GatheringSpawnerLocation> loadAll() {
        File file = file();
        if (!file.exists()) {
            return List.of();
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<GatheringSpawnerLocation> result = new ArrayList<>();
        for (var map : yaml.getMapList("spawners")) {
            String id = stringValue(map.get("id"));
            String world = stringValue(map.get("world"));
            if (id == null || world == null) {
                continue;
            }
            result.add(new GatheringSpawnerLocation(
                    id,
                    world,
                    intValue(map.get("x")),
                    intValue(map.get("y")),
                    intValue(map.get("z"))
            ));
        }
        return result;
    }

    public boolean saveAll(@NotNull Iterable<GatheringSpawnerLocation> locations) {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GatheringSpawnerLocation location : locations) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", location.spawnerId());
            row.put("world", location.worldName());
            row.put("x", location.x());
            row.put("y", location.y());
            row.put("z", location.z());
            rows.add(row);
        }
        yaml.set("spawners", rows);

        File file = file();
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Logger.log(LogId.E_6401, "gathering_spawner", parent);
            return false;
        }
        try {
            yaml.save(file);
            return true;
        } catch (IOException ex) {
            Logger.log(LogId.E_6400, ex, "gathering_spawner", file, ex.getMessage());
            return false;
        }
    }

    private @NotNull File file() {
        return new File(plugin.getDataFolder(), FILE_NAME);
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
