package io.github.maaasu.astralRecord.feature.serverrecord.repository;

import io.github.maaasu.astralRecord.feature.serverrecord.model.ServerRecordPlacement;
import io.github.maaasu.astralRecord.feature.serverrecord.model.ServerRecordType;
import io.github.maaasu.astralRecord.feature.textdisplay.model.TextDisplayPlacement;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 表示位置と帰還位置をプラグインのローカルYAMLへ保存します。 */
public final class ServerRecordPlacementRepository {
    private final File file;

    /** @param plugin 保存先のプラグイン @param fileName データフォルダ内の固定ファイル名 */
    public ServerRecordPlacementRepository(Plugin plugin, String fileName) {
        this.file = new File(plugin.getDataFolder(), fileName);
    }

    /** @return 保存された配置一覧 @throws IllegalStateException YAML不正時。既存ファイルを上書きしません */
    public List<ServerRecordPlacement> loadAll() {
        if (!file.exists()) return List.of();
        var yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            if (!yaml.isList("placements")) throw new IllegalArgumentException("Missing placements list");
            var result = new ArrayList<ServerRecordPlacement>();
            var ids = new java.util.HashSet<String>();
            for (Object raw : yaml.getList("placements")) {
                if (!(raw instanceof Map<?, ?> row)) throw new IllegalArgumentException("Invalid placement row");
                String id = required(row, "id");
                ServerRecordType type = ServerRecordType.parse(required(row, "type"));
                if (!id.matches("[a-zA-Z0-9_-]{1,64}") || type == null || !ids.add(id)) {
                    throw new IllegalArgumentException("Invalid placement type or ID");
                }
                result.add(new ServerRecordPlacement(type, new TextDisplayPlacement(id, "", required(row, "world"),
                        number(row, "x"), number(row, "y"), number(row, "z"),
                        (float) number(row, "yaw"), (float) number(row, "pitch"))));
            }
            return List.copyOf(result);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read server record placements: " + file.getName(), failure);
        }
    }

    /**
     * 一時ファイルから置換し、保存失敗時に既存ファイルを保持します。
     * @param placements 保存する全配置
     * @throws IllegalStateException 保存失敗時
     */
    public void saveAll(Collection<ServerRecordPlacement> placements) {
        var yaml = new YamlConfiguration();
        var rows = new ArrayList<Map<String, Object>>();
        for (ServerRecordPlacement placement : placements) {
            var anchor = placement.anchor();
            var row = new LinkedHashMap<String, Object>();
            row.put("id", anchor.id());
            row.put("type", placement.type().id());
            row.put("world", anchor.worldName());
            row.put("x", anchor.x());
            row.put("y", anchor.y());
            row.put("z", anchor.z());
            row.put("yaw", anchor.yaw());
            row.put("pitch", anchor.pitch());
            rows.add(row);
        }
        yaml.set("placements", rows);
        File temporary = null;
        try {
            Files.createDirectories(file.toPath().getParent());
            temporary = File.createTempFile("server-record-", ".tmp", file.getParentFile());
            yaml.save(temporary);
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot save server record placements: " + file.getName(), failure);
        } finally {
            if (temporary != null && temporary.exists()) temporary.delete();
        }
    }

    /** @param row YAML行 @param key 必須文字列キー @return 空でない文字列 */
    private static String required(Map<?, ?> row, String key) {
        Object raw = row.get(key);
        if (!(raw instanceof String value) || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    /** @param row YAML行 @param key 必須数値キー @return 有限な数値 */
    private static double number(Map<?, ?> row, String key) {
        Object raw = row.get(key);
        if (!(raw instanceof Number value) || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException("Invalid " + key);
        }
        return value.doubleValue();
    }
}
