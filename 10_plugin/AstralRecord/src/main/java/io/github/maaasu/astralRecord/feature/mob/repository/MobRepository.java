package io.github.maaasu.astralRecord.feature.mob.repository;

import io.github.maaasu.astralRecord.feature.mob.model.MobBaseStat;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager;
import io.github.maaasu.astralRecord.infrastructure.util.YamlLoaderUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mob YAML 定義を読み込むリポジトリです。
 */
public class MobRepository {

    private static final String MOB_DIRECTORY = "40.features.mob";

    /**
     * すべての Mob テンプレートを読み込みます。
     *
     * @return テンプレート ID をキーにした Mob テンプレート
     */
    @NotNull
    public Map<String, MobTemplate> findAll() {
        File root = FileDatabaseManager.getInstance().getRootDirectory();
        if (root == null) {
            return Map.of();
        }

        File mobDirectory = new File(root, MOB_DIRECTORY);
        Map<String, YamlConfiguration> yamlMap = YamlLoaderUtil.loadAllFromDirectory(mobDirectory, true);
        Map<String, MobTemplate> result = new LinkedHashMap<>();

        for (YamlConfiguration yaml : yamlMap.values()) {
            MobTemplate template = parse(yaml);
            if (template != null) {
                result.put(template.id(), template);
            }
        }
        return result;
    }

    @Nullable
    private MobTemplate parse(@NotNull YamlConfiguration yaml) {
        String id = yaml.getString("id");
        String entityTypeName = yaml.getString("entityType");
        if (id == null || id.isBlank() || entityTypeName == null || entityTypeName.isBlank()) {
            return null;
        }

        EntityType entityType;
        try {
            entityType = EntityType.valueOf(entityTypeName.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }

        return new MobTemplate(
                yaml.getInt("schemaVersion", 1),
                id,
                MobCategory.from(yaml.getString("category")),
                yaml.getString("name", id),
                yaml.getString("title"),
                yaml.getInt("level", 1),
                entityType,
                yaml.getBoolean("nameVisible", true),
                parseStats(yaml),
                parseIdle(yaml.getConfigurationSection("ai.idle"))
        );
    }

    @NotNull
    private List<MobBaseStat> parseStats(@NotNull YamlConfiguration yaml) {
        List<Map<?, ?>> statMaps = yaml.getMapList("baseStats");
        List<MobBaseStat> stats = new ArrayList<>();
        for (Map<?, ?> statMap : statMaps) {
            Object status = statMap.get("status");
            Object value = statMap.get("value");
            if (status == null || value == null) {
                continue;
            }
            double number = value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString());
            stats.add(new MobBaseStat(status.toString(), number));
        }
        return List.copyOf(stats);
    }

    @NotNull
    private MobIdleConfig parseIdle(@Nullable ConfigurationSection section) {
        if (section == null) {
            return new MobIdleConfig("STATIONARY", 10.0, 1.0);
        }
        return new MobIdleConfig(
                section.getString("behavior", "STATIONARY"),
                section.getDouble("wanderRadius", 10.0),
                section.getDouble("speed", 1.0)
        );
    }
}
