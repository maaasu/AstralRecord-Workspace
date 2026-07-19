package io.github.maaasu.astralRecord.feature.textdisplay.repository;

import io.github.maaasu.astralRecord.feature.textdisplay.model.TextDisplayPlacement;
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

/**
 * プラグインデータフォルダ配下の固定 TextDisplay 配置 YAML を読み書きします。
 */
public final class TextDisplayPlacementRepository {

    private static final String FILE_NAME = "text_displays.yml";
    private static final String ROOT_KEY = "textDisplays";

    private final Plugin plugin;

    /**
     * リポジトリを初期化します。
     *
     * @param plugin プラグイン本体
     */
    public TextDisplayPlacementRepository(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 保存済みの固定 TextDisplay 配置を読み込みます。
     *
     * @return 固定 TextDisplay 配置一覧
     */
    @NotNull
    public List<TextDisplayPlacement> loadAll() {
        File file = file();
        if (!file.exists()) {
            return List.of();
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<TextDisplayPlacement> result = new ArrayList<>();
        for (Map<?, ?> map : yaml.getMapList(ROOT_KEY)) {
            String id = stringValue(map.get("id"));
            String text = stringValue(map.get("text"));
            String world = stringValue(map.get("world"));
            if (id == null || id.isBlank() || text == null || world == null) {
                continue;
            }
            result.add(new TextDisplayPlacement(
                    id,
                    text,
                    world,
                    doubleValue(map.get("x")),
                    doubleValue(map.get("y")),
                    doubleValue(map.get("z")),
                    floatValue(map.get("yaw")),
                    floatValue(map.get("pitch"))
            ));
        }
        return result;
    }

    /**
     * 固定 TextDisplay 配置一覧を YAML に保存します。
     *
     * @param placements 保存対象
     */
    public boolean saveAll(@NotNull Iterable<TextDisplayPlacement> placements) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("AstralRecord fixed TextDisplay placements. Legacy color codes using & are supported."));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TextDisplayPlacement placement : placements) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", placement.id());
            row.put("text", placement.text());
            row.put("world", placement.worldName());
            row.put("x", placement.x());
            row.put("y", placement.y());
            row.put("z", placement.z());
            row.put("yaw", placement.yaw());
            row.put("pitch", placement.pitch());
            rows.add(row);
        }
        yaml.set(ROOT_KEY, rows);

        File file = file();
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Logger.log(LogId.E_6401, "text_display", parent);
            return false;
        }
        try {
            yaml.save(file);
            return true;
        } catch (IOException ex) {
            Logger.log(LogId.E_6400, ex, "text_display", file, ex.getMessage());
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
}
