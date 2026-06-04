package io.github.maaasu.astralRecord.feature.skilltree.repository;

import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeStatusDefinition;
import io.github.maaasu.astralRecord.feature.status.model.StatusModifierType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * filebase のスキルツリーノード定義を読み込むリポジトリです。
 */
public class SkillTreeNodeRepository {
    private static final String RELATIVE_PATH = "35.features.skilltree";

    @NotNull
    public List<SkillTreeNodeDefinition> findAll() {
        File root = FileDatabaseManager.getInstance().getRootDirectory();
        if (root == null) {
            return List.of();
        }
        File directory = new File(root, RELATIVE_PATH);
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml") && !name.contains("スキーマ"));
        if (files == null || files.length == 0) {
            return List.of();
        }

        List<SkillTreeNodeDefinition> result = new ArrayList<>();
        for (File file : files) {
            SkillTreeNodeDefinition definition = parse(YamlConfiguration.loadConfiguration(file));
            if (definition != null) {
                result.add(definition);
            }
        }
        return result;
    }

    @Nullable
    private SkillTreeNodeDefinition parse(@NotNull YamlConfiguration yaml) {
        String id = yaml.getString("id");
        String positionId = yaml.getString("positionId");
        if (id == null || id.isBlank() || positionId == null || positionId.isBlank()) {
            return null;
        }
        return new SkillTreeNodeDefinition(
                id.trim(),
                positionId.trim(),
                yaml.getString("name", id).trim(),
                resolveMaterial(yaml.getString("icon")),
                yaml.getStringList("lore"),
                yaml.getStringList("tags"),
                yaml.getStringList("skillIds"),
                parseStatuses(yaml)
        );
    }

    private @NotNull List<SkillTreeNodeStatusDefinition> parseStatuses(@NotNull YamlConfiguration yaml) {
        List<Map<?, ?>> rows = yaml.getMapList("statuses");
        if (rows.isEmpty()) {
            return List.of();
        }

        List<SkillTreeNodeStatusDefinition> result = new ArrayList<>();
        for (Map<?, ?> row : rows) {
            Object rawStatus = row.get("status");
            if (!(rawStatus instanceof String status) || status.isBlank()) {
                continue;
            }
            StatusType statusType = resolveStatusType(status);
            if (statusType == null) {
                continue;
            }
            Object rawValue = row.get("value");
            if (!(rawValue instanceof Number number)) {
                continue;
            }
            Object rawType = row.get("type");
            result.add(new SkillTreeNodeStatusDefinition(
                    statusType,
                    rawType instanceof String type ? StatusModifierType.fromRaw(type) : StatusModifierType.FLAT,
                    number.doubleValue()
            ));
        }
        return result;
    }

    @NotNull
    private Material resolveMaterial(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Material.NETHER_STAR;
        }
        Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        return material == null || material == Material.AIR || !material.isItem() ? Material.NETHER_STAR : material;
    }

    private @Nullable StatusType resolveStatusType(@NotNull String raw) {
        try {
            return StatusType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
