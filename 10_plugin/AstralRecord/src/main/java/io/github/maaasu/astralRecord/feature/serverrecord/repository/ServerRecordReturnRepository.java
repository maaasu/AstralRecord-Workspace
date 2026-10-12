package io.github.maaasu.astralRecord.feature.serverrecord.repository;

import io.github.maaasu.astralRecord.feature.textdisplay.model.TextDisplayPlacement;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 再接続・再起動を跨いで訪問前の位置を保存します。 */
public final class ServerRecordReturnRepository {
    private final File file;

    /** @param plugin 保存先プラグイン */
    public ServerRecordReturnRepository(Plugin plugin) {
        file = new File(plugin.getDataFolder(), "server_record_returns.yml");
    }

    /** @return UUIDごとの帰還位置。破損時は例外となり上書きを防ぎます */
    public Map<UUID, TextDisplayPlacement> loadAll() {
        var result = new LinkedHashMap<UUID, TextDisplayPlacement>();
        if (!file.exists()) return result;
        try {
            var yaml = new YamlConfiguration();
            yaml.load(file);
            var root = yaml.getConfigurationSection("returns");
            if (root == null) throw new IllegalArgumentException("Missing returns");
            for (String id : root.getKeys(false)) {
                var row = root.getConfigurationSection(id);
                if (row == null || !row.isString("world") || row.getString("world").isBlank()) {
                    throw new IllegalArgumentException("Invalid return location");
                }
                for (String key : new String[]{"x", "y", "z", "yaw", "pitch"}) {
                    if (!row.isSet(key) || !Double.isFinite(row.getDouble(key, Double.NaN))) {
                        throw new IllegalArgumentException("Invalid return coordinate");
                    }
                }
                result.put(UUID.fromString(id), new TextDisplayPlacement(id, "", row.getString("world"),
                        row.getDouble("x"), row.getDouble("y"), row.getDouble("z"),
                        (float) row.getDouble("yaw"), (float) row.getDouble("pitch")));
            }
            return result;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read server record returns", failure);
        }
    }

    /** @param returns 全帰還位置 @throws IllegalStateException 保存失敗時。既存ファイルを保持します */
    public void saveAll(Map<UUID, TextDisplayPlacement> returns) {
        var yaml = new YamlConfiguration();
        yaml.createSection("returns");
        returns.forEach((id, location) -> {
            String prefix = "returns." + id + ".";
            yaml.set(prefix + "world", location.worldName());
            yaml.set(prefix + "x", location.x());
            yaml.set(prefix + "y", location.y());
            yaml.set(prefix + "z", location.z());
            yaml.set(prefix + "yaw", location.yaw());
            yaml.set(prefix + "pitch", location.pitch());
        });
        File temporary = null;
        try {
            Files.createDirectories(file.toPath().getParent());
            temporary = File.createTempFile("server-record-return-", ".tmp", file.getParentFile());
            yaml.save(temporary);
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot save server record returns", failure);
        } finally {
            if (temporary != null && temporary.exists()) temporary.delete();
        }
    }
}
