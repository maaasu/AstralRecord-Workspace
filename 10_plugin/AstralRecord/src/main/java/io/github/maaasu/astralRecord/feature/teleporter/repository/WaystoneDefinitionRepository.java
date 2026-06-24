package io.github.maaasu.astralRecord.feature.teleporter.repository;

import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plugin data folder の waystones.yml を読み書きします。
 */
public final class WaystoneDefinitionRepository {
    private static final String FILE_NAME = "waystones.yml";
    private static final String ROOT_KEY = "waystones";

    private final Plugin plugin;

    public WaystoneDefinitionRepository(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 保存済みウェイストーン定義をすべて読み込みます。
     *
     * @return 読み込めたウェイストーン定義一覧
     */
    @NotNull
    public List<WaystoneDefinition> loadAll() {
        File file = file();
        if (!file.exists()) {
            return List.of();
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<WaystoneDefinition> result = new ArrayList<>();
        for (Map<?, ?> map : yaml.getMapList(ROOT_KEY)) {
            String id = stringValue(map.get("id"));
            String name = stringValue(map.get("name"));
            String world = stringValue(map.get("worldName"));
            if (world == null || world.isBlank()) {
                world = stringValue(map.get("world"));
            }
            if (id == null || id.isBlank() || name == null || name.isBlank() || world == null || world.isBlank()) {
                continue;
            }
            result.add(new WaystoneDefinition(
                    id.trim(),
                    name.trim(),
                    world.trim(),
                    doubleValue(map.get("x")),
                    doubleValue(map.get("y")),
                    doubleValue(map.get("z")),
                    floatValue(map.get("yaw")),
                    floatValue(map.get("pitch")),
                    booleanValue(map.get("lockEnabled")),
                    Math.max(0L, longValue(map.get("unlockGold"))),
                    instantValue(map.get("createdAt")),
                    stringValue(map.get("createdBy"), "system")
            ));
        }
        return result;
    }

    /**
     * ウェイストーン定義一覧を waystones.yml に保存します。
     *
     * @param definitions 保存対象
     */
    public void saveAll(@NotNull Iterable<WaystoneDefinition> definitions) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().header("AstralRecord waystone master data. Do not change id values manually.");
        yaml.set("version", 1);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WaystoneDefinition definition : definitions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", definition.id());
            row.put("name", definition.name());
            row.put("worldName", definition.worldName());
            row.put("x", definition.x());
            row.put("y", definition.y());
            row.put("z", definition.z());
            row.put("yaw", definition.yaw());
            row.put("pitch", definition.pitch());
            row.put("lockEnabled", definition.lockEnabled());
            row.put("unlockGold", definition.unlockGold());
            row.put("createdAt", definition.createdAt().toString());
            row.put("createdBy", definition.createdBy());
            rows.add(row);
        }
        yaml.set(ROOT_KEY, rows);

        File file = file();
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            Logger.log(LogId.E_5953, e, file.getAbsolutePath());
        }
    }

    @NotNull
    private File file() {
        return new File(plugin.getDataFolder(), FILE_NAME);
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String stringValue(Object value, @NotNull String fallback) {
        String text = stringValue(value);
        return text == null || text.isBlank() ? fallback : text;
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0.0D : Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return 0.0D;
        }
    }

    private float floatValue(Object value) {
        if (value instanceof Number number) {
            return number.floatValue();
        }
        try {
            return value == null ? 0.0F : Float.parseFloat(value.toString());
        } catch (NumberFormatException ignored) {
            return 0.0F;
        }
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : value != null && Boolean.parseBoolean(value.toString());
    }

    private Instant instantValue(Object value) {
        if (value == null) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(value.toString());
        } catch (RuntimeException ignored) {
            return Instant.EPOCH;
        }
    }
}
