package io.github.maaasu.astralRecord.feature.dungeon.repository;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager;
import io.github.maaasu.astralRecord.infrastructure.util.MaterialNameResolver;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** filebase の {@code 65.features.dungeon} からダンジョン定義を読み込みます。 */
public final class DungeonDefinitionRepository {
    private static final String RELATIVE_PATH = "65.features.dungeon";

    /**
     * すべてのダンジョン定義をファイル名順に読み込みます。
     *
     * @return ダンジョン定義一覧
     * @throws IllegalArgumentException いずれかの定義が不正な場合
     */
    public @NotNull List<DungeonDefinition> findAll() {
        File root = FileDatabaseManager.getInstance().getRootDirectory();
        if (root == null) {
            return List.of();
        }
        File directory = new File(root, RELATIVE_PATH);
        File[] files = directory.listFiles((ignored, name) ->
                name.endsWith(".yml") && !name.endsWith("定義.yml"));
        if (files == null || files.length == 0) {
            return List.of();
        }
        Arrays.sort(files, Comparator.comparing(File::getName));

        List<DungeonDefinition> definitions = new ArrayList<>(files.length);
        for (File file : files) {
            try {
                definitions.add(parse(YamlConfiguration.loadConfiguration(file)));
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("Invalid dungeon master: " + file.getPath(), ex);
            }
        }
        return List.copyOf(definitions);
    }

    private @NotNull DungeonDefinition parse(@NotNull YamlConfiguration yaml) {
        int schemaVersion = yaml.getInt("schemaVersion", -1);
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("schemaVersion must be 1");
        }

        ConfigurationSection generation = requireSection(yaml, "generation");
        ConfigurationSection area = requireSection(generation, "area");
        ConfigurationSection splitRatio = requireSection(generation, "splitRatio");
        ConfigurationSection theme = requireSection(yaml, "theme");
        ConfigurationSection pillar = requireSection(theme, "pillar");
        ConfigurationSection encounter = requireSection(yaml, "encounter");

        return new DungeonDefinition(
                schemaVersion,
                requireString(yaml, "id"),
                requireString(yaml, "displayName"),
                stripPrefix(requireString(yaml, "worldRef")),
                parseRange(requireSection(yaml, "party"), "party"),
                new DungeonDefinition.Generation(
                        requireInt(area, "width"),
                        requireInt(area, "depth"),
                        requireInt(generation, "baseY"),
                        parseRange(requireSection(generation, "roomCount"), "generation.roomCount"),
                        parseRange(requireSection(generation, "roomSize"), "generation.roomSize"),
                        requireInt(generation, "roomHeight"),
                        requireInt(generation, "corridorWidth"),
                        requireInt(generation, "corridorHeight"),
                        requireDouble(splitRatio, "min"),
                        requireDouble(splitRatio, "max"),
                        parseShapes(generation)
                ),
                new DungeonDefinition.Theme(
                        parseMaterials(theme, "floor"),
                        parseMaterials(theme, "wall"),
                        parseMaterials(theme, "ceiling"),
                        parseMaterials(theme, "corridor"),
                        requireBlock(theme, "gateMaterial", true),
                        new DungeonDefinition.Pillar(
                                pillar.getBoolean("enabled", false),
                                requireDouble(pillar, "chance"),
                                requireBlock(pillar, "material", true),
                                requireStairs(pillar, "stairMaterial")
                        )
                ),
                new DungeonDefinition.Encounter(
                        parseMobs(encounter),
                        parseRange(requireSection(encounter, "mobsPerRoom"), "encounter.mobsPerRoom"),
                        requireInt(encounter, "firstCombatRoomMaxMobLevel"),
                        stripPrefix(requireString(encounter, "bossMobId"))
                )
        );
    }

    private @NotNull List<DungeonDefinition.WeightedShape> parseShapes(
            @NotNull ConfigurationSection generation
    ) {
        List<DungeonDefinition.WeightedShape> result = new ArrayList<>();
        for (Map<?, ?> entry : generation.getMapList("roomShapes")) {
            String type = requiredMapString(entry, "type", "generation.roomShapes");
            result.add(new DungeonDefinition.WeightedShape(
                    DungeonRoomShape.from(type),
                    requiredMapInt(entry, "weight", "generation.roomShapes")
            ));
        }
        return List.copyOf(result);
    }

    private @NotNull List<DungeonDefinition.WeightedMaterial> parseMaterials(
            @NotNull ConfigurationSection theme,
            @NotNull String key
    ) {
        List<DungeonDefinition.WeightedMaterial> result = new ArrayList<>();
        for (Map<?, ?> entry : theme.getMapList(key)) {
            String rawMaterial = requiredMapString(entry, "material", "theme." + key);
            Material material = resolveBlock(rawMaterial, "theme." + key, true);
            result.add(new DungeonDefinition.WeightedMaterial(
                    material,
                    requiredMapInt(entry, "weight", "theme." + key)
            ));
        }
        return List.copyOf(result);
    }

    private @NotNull List<DungeonDefinition.WeightedMob> parseMobs(
            @NotNull ConfigurationSection encounter
    ) {
        List<DungeonDefinition.WeightedMob> result = new ArrayList<>();
        for (Map<?, ?> entry : encounter.getMapList("normalMobPool")) {
            result.add(new DungeonDefinition.WeightedMob(
                    stripPrefix(requiredMapString(entry, "mobId", "encounter.normalMobPool")),
                    requiredMapInt(entry, "weight", "encounter.normalMobPool")
            ));
        }
        return List.copyOf(result);
    }

    private @NotNull DungeonDefinition.IntRange parseRange(
            @NotNull ConfigurationSection section,
            @NotNull String path
    ) {
        return new DungeonDefinition.IntRange(
                requireInt(section, "min", path),
                requireInt(section, "max", path)
        );
    }

    private @NotNull ConfigurationSection requireSection(
            @NotNull ConfigurationSection parent,
            @NotNull String key
    ) {
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section == null) {
            throw new IllegalArgumentException("Missing section: " + key);
        }
        return section;
    }

    private @NotNull String requireString(
            @NotNull ConfigurationSection section,
            @NotNull String key
    ) {
        String value = section.getString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing string: " + key);
        }
        return value.trim();
    }

    private int requireInt(@NotNull ConfigurationSection section, @NotNull String key) {
        return requireInt(section, key, key);
    }

    private int requireInt(
            @NotNull ConfigurationSection section,
            @NotNull String key,
            @NotNull String path
    ) {
        Object value = section.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Missing integer: " + path + "." + key);
        }
        return number.intValue();
    }

    private double requireDouble(@NotNull ConfigurationSection section, @NotNull String key) {
        Object value = section.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Missing number: " + key);
        }
        return number.doubleValue();
    }

    private @NotNull Material requireBlock(
            @NotNull ConfigurationSection section,
            @NotNull String key,
            boolean solid
    ) {
        return resolveBlock(requireString(section, key), key, solid);
    }

    private @NotNull Material requireStairs(
            @NotNull ConfigurationSection section,
            @NotNull String key
    ) {
        Material material = resolveBlock(requireString(section, key), key, false);
        if (!material.name().endsWith("_STAIRS")) {
            throw new IllegalArgumentException(key + " must be a stairs Material");
        }
        return material;
    }

    private @NotNull Material resolveBlock(
            @NotNull String raw,
            @NotNull String path,
            boolean solid
    ) {
        Material material = MaterialNameResolver.match(raw);
        if (material == null || !material.isBlock() || material.isAir() || (solid && !material.isSolid())) {
            throw new IllegalArgumentException("Invalid block Material at " + path + ": " + raw);
        }
        return material;
    }

    private @NotNull String requiredMapString(
            @NotNull Map<?, ?> entry,
            @NotNull String key,
            @NotNull String path
    ) {
        Object value = entry.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing string: " + path + "." + key);
        }
        return value.toString().trim();
    }

    private int requiredMapInt(
            @NotNull Map<?, ?> entry,
            @NotNull String key,
            @NotNull String path
    ) {
        Object value = entry.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("Missing integer: " + path + "." + key);
    }

    private @NotNull String stripPrefix(@NotNull String raw) {
        int separator = raw.indexOf(':');
        return separator < 0 ? raw.trim() : raw.substring(separator + 1).trim();
    }
}
