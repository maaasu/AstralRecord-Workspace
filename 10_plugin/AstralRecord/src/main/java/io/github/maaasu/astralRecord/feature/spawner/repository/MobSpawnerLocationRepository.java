package io.github.maaasu.astralRecord.feature.spawner.repository;

import io.github.maaasu.astralRecord.feature.spawner.model.MobSpawnerLocation;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * プラグインデータフォルダ配下のスポナー座標ファイルを読み書きします。
 */
public class MobSpawnerLocationRepository {

    private static final String FILE_NAME = "mob_spawners.yml";

    private final Plugin plugin;

    /**
     * リポジトリを初期化します。
     *
     * @param plugin プラグイン本体
     */
    public MobSpawnerLocationRepository(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 保存済みのスポナー座標を一括ロードします。
     *
     * @return スポナー座標一覧
     */
    @NotNull
    public List<MobSpawnerLocation> loadAll() {
        File file = file();
        if (!file.exists()) {
            return List.of();
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<MobSpawnerLocation> result = new ArrayList<>();
        for (var map : yaml.getMapList("spawners")) {
            String id = stringValue(map.get("id"));
            String world = stringValue(map.get("world"));
            if (id == null || world == null) {
                continue;
            }
            result.add(new MobSpawnerLocation(
                    id,
                    world,
                    intValue(map.get("x")),
                    intValue(map.get("y")),
                    intValue(map.get("z"))
            ));
        }
        return result;
    }

    /**
     * キャッシュ中のスポナー座標を保存します。
     *
     * @param locations 保存対象
     */
    public boolean saveAll(@NotNull Iterable<MobSpawnerLocation> locations) {
        YamlConfiguration yaml = new YamlConfiguration();
        List<java.util.Map<String, Object>> rows = new ArrayList<>();
        for (MobSpawnerLocation location : locations) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
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
            Logger.log(LogId.E_6401, "mob_spawner", parent);
            return false;
        }
        try {
            yaml.save(file);
            return true;
        } catch (IOException ex) {
            Logger.log(LogId.E_6400, ex, "mob_spawner", file, ex.getMessage());
            return false;
        }
    }

    @NotNull
    private File file() {
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
