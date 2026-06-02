package io.github.maaasu.astralRecord.feature.skilltree.repository;

import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeEdge;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePosition;
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
 * plugin data に保存されるスキルツリー構造定義のリポジトリです。
 */
public class SkillTreeStructureRepository {
    private static final String FILE_NAME = "skilltree_structure.yml";

    private final Plugin plugin;

    public SkillTreeStructureRepository(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    @NotNull
    public StructureSnapshot load() {
        File file = file();
        if (!file.exists()) {
            return new StructureSnapshot(List.of(), List.of());
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<SkillTreePosition> positions = new ArrayList<>();
        for (var map : yaml.getMapList("positions")) {
            String id = stringValue(map.get("id"));
            String world = stringValue(map.get("world"));
            if (id == null || world == null) {
                continue;
            }
            positions.add(new SkillTreePosition(id, world, intValue(map.get("x")), intValue(map.get("y")), intValue(map.get("z"))));
        }

        List<SkillTreeEdge> edges = new ArrayList<>();
        for (var map : yaml.getMapList("edges")) {
            String left = stringValue(map.get("left"));
            String right = stringValue(map.get("right"));
            if (left == null || right == null || left.equals(right)) {
                continue;
            }
            edges.add(new SkillTreeEdge(left, right));
        }
        return new StructureSnapshot(positions, edges);
    }

    public void save(@NotNull Iterable<SkillTreePosition> positions, @NotNull Iterable<SkillTreeEdge> edges) {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> positionRows = new ArrayList<>();
        for (SkillTreePosition position : positions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", position.positionId());
            row.put("world", position.worldName());
            row.put("x", position.x());
            row.put("y", position.y());
            row.put("z", position.z());
            positionRows.add(row);
        }
        yaml.set("positions", positionRows);

        List<Map<String, Object>> edgeRows = new ArrayList<>();
        for (SkillTreeEdge edge : edges) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("left", edge.leftPositionId());
            row.put("right", edge.rightPositionId());
            edgeRows.add(row);
        }
        yaml.set("edges", edgeRows);

        File file = file();
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try {
            yaml.save(file);
        } catch (IOException ignored) {
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

    public record StructureSnapshot(
            @NotNull List<SkillTreePosition> positions,
            @NotNull List<SkillTreeEdge> edges
    ) {
    }
}
