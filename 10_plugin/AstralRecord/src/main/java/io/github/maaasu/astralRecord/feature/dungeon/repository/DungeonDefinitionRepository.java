package io.github.maaasu.astralRecord.feature.dungeon.repository;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomType;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropItem;
import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager;
import io.github.maaasu.astralRecord.infrastructure.util.MaterialNameResolver;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    /** 最小構成を既定値で補完し、一つの YAML を実行時定義へ変換します。 */
    private @NotNull DungeonDefinition parse(@NotNull YamlConfiguration yaml) {
        int schemaVersion = yaml.getInt("schemaVersion", -1);
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("schemaVersion must be 1");
        }

        ConfigurationSection entry = requireSection(yaml, "entry");
        ConfigurationSection generation = yaml.getConfigurationSection("generation");
        ConfigurationSection area = generation == null ? null : generation.getConfigurationSection("area");
        ConfigurationSection splitRatio = generation == null ? null : generation.getConfigurationSection("splitRatio");
        ConfigurationSection theme = yaml.getConfigurationSection("theme");
        ConfigurationSection pillar = theme == null ? null : theme.getConfigurationSection("pillar");
        ConfigurationSection decorations = theme == null ? null : theme.getConfigurationSection("decorations");
        ConfigurationSection encounter = requireSection(yaml, "encounter");
        ConfigurationSection challenge = yaml.getConfigurationSection("challenge");
        ConfigurationSection clearRewards = yaml.getConfigurationSection("clearRewards");

        return new DungeonDefinition(
                schemaVersion,
                requireString(yaml, "id"),
                requireString(yaml, "displayName"),
                requireInt(yaml, "recommendedLevel"),
                new DungeonDefinition.Entry(
                        stripPrefix(requireString(entry, "worldRef")),
                        requireDouble(entry, "x"),
                        requireDouble(entry, "y"),
                        requireDouble(entry, "z"),
                        (float) optionalDouble(entry, "yaw", 0.0D),
                        (float) optionalDouble(entry, "pitch", 0.0D),
                        optionalDouble(entry, "radius", 2.0D)
                ),
                parseRange(yaml.getConfigurationSection("party"), "party", 1, 4),
                new DungeonDefinition.Challenge(
                        optionalInt(challenge, "deathLimit", 5),
                        optionalInt(challenge, "reviveDelaySeconds", 5)
                ),
                new DungeonDefinition.Generation(
                        optionalInt(area, "width", 128),
                        optionalInt(area, "depth", 128),
                        optionalInt(generation, "baseY", 64),
                        parseRange(section(generation, "roomCount"), "generation.roomCount", 7, 11),
                        parseRange(section(generation, "roomSize"), "generation.roomSize", 11, 23),
                        optionalInt(generation, "roomHeight", 8),
                        optionalInt(generation, "corridorWidth", 3),
                        optionalInt(generation, "corridorHeight", 4),
                        optionalDouble(splitRatio, "min", 0.35D),
                        optionalDouble(splitRatio, "max", 0.50D),
                        parseShapes(generation),
                        parseRoomTypes(generation)
                ),
                new DungeonDefinition.Theme(
                        parseMaterials(theme, "floor"),
                        parseMaterials(theme, "wall"),
                        parseMaterials(theme, "ceiling"),
                        parseMaterials(theme, "corridor"),
                        optionalBlock(theme, "gateMaterial", Material.IRON_BARS, true),
                        new DungeonDefinition.Pillar(
                                pillar != null && pillar.getBoolean("enabled", false),
                                optionalDouble(pillar, "chance", 0.35D),
                                optionalBlock(pillar, "material", Material.CHISELED_STONE_BRICKS, true),
                                optionalStairs(pillar, "stairMaterial", Material.STONE_BRICK_STAIRS)
                        ),
                        optionalBlock(theme, "lightMaterial", Material.TORCH, false),
                        new DungeonDefinition.Decorations(
                                optionalBlock(decorations, "supportMaterial", Material.OAK_LOG, true),
                                optionalBlock(decorations, "beamMaterial", Material.OAK_PLANKS, true),
                                parseMaterials(decorations, "rubble", Material.COBBLESTONE),
                                parseMaterials(decorations, "accent", Material.COAL_ORE)
                        )
                ),
                new DungeonDefinition.Encounter(
                        parseMobs(encounter),
                        parseRange(encounter.getConfigurationSection("mobsPerRoom"), "encounter.mobsPerRoom", 2, 4),
                        optionalInt(encounter, "firstCombatRoomMaxMobLevel", 10),
                        stripPrefix(requireString(encounter, "bossMobId")),
                        optionalNullableInt(encounter, "bossMobLevel")
                ),
                parseClearRewards(clearRewards)
        );
    }

    /** クリア時のプレイヤー別抽選報酬を既存 drops 形式へ変換します。 */
    private @NotNull MobDropConfig parseClearRewards(ConfigurationSection section) {
        if (section == null) {
            return new MobDropConfig(0, null, List.of(), null);
        }
        List<MobDropItem> items = new ArrayList<>();
        for (Map<?, ?> entry : section.getMapList("items")) {
            items.add(new MobDropItem(
                    stripPrefix(requiredMapString(entry, "itemId", "clearRewards.items")),
                    optionalMapDouble(entry, "rate", 100.0D),
                    optionalMapAmount(entry, "amount", "1"),
                    false,
                    false
            ));
        }
        String lootTable = optionalString(section, "lootTable");
        return new MobDropConfig(
                0,
                null,
                items,
                lootTable == null ? null : stripPrefix(lootTable)
        );
    }

    /** 省略時は長方形優先の既定形状一覧を返します。 */
    private @NotNull List<DungeonDefinition.WeightedShape> parseShapes(
            ConfigurationSection generation
    ) {
        if (generation == null || !generation.isList("roomShapes")) {
            return List.of(
                    new DungeonDefinition.WeightedShape(DungeonRoomShape.RECTANGLE, 3),
                    new DungeonDefinition.WeightedShape(DungeonRoomShape.CYLINDER, 1)
            );
        }
        List<DungeonDefinition.WeightedShape> result = new ArrayList<>();
        for (Map<?, ?> entry : generation.getMapList("roomShapes")) {
            String type = requiredMapString(entry, "type", "generation.roomShapes");
            result.add(new DungeonDefinition.WeightedShape(
                    DungeonRoomShape.from(type),
                    optionalMapInt(entry, "weight", 1)
            ));
        }
        return List.copyOf(result);
    }

    /** 省略時は装飾なしの標準部屋だけを返します。 */
    private @NotNull List<DungeonDefinition.WeightedRoomType> parseRoomTypes(
            ConfigurationSection generation
    ) {
        if (generation == null || !generation.isList("roomTypes")) {
            return List.of(new DungeonDefinition.WeightedRoomType(DungeonRoomType.STANDARD, 1));
        }
        List<DungeonDefinition.WeightedRoomType> result = new ArrayList<>();
        for (Map<?, ?> entry : generation.getMapList("roomTypes")) {
            String type = requiredMapString(entry, "type", "generation.roomTypes");
            result.add(new DungeonDefinition.WeightedRoomType(
                    DungeonRoomType.from(type),
                    optionalMapInt(entry, "weight", 1)
            ));
        }
        return List.copyOf(result);
    }

    /** 対象テーマ一覧を読み、未定義時は用途別の既定 Material を返します。 */
    private @NotNull List<DungeonDefinition.WeightedMaterial> parseMaterials(
            ConfigurationSection theme,
            @NotNull String key
    ) {
        Material fallback = key.equals("corridor") ? Material.COBBLESTONE : Material.STONE_BRICKS;
        return parseMaterials(theme, key, fallback);
    }

    /** 対象テーマ一覧を読み、未定義時は明示した既定 Material を返します。 */
    private @NotNull List<DungeonDefinition.WeightedMaterial> parseMaterials(
            ConfigurationSection theme,
            @NotNull String key,
            @NotNull Material fallback
    ) {
        if (theme == null || !theme.isList(key)) {
            return List.of(new DungeonDefinition.WeightedMaterial(fallback, 1));
        }
        List<DungeonDefinition.WeightedMaterial> result = new ArrayList<>();
        for (Map<?, ?> entry : theme.getMapList(key)) {
            String rawMaterial = requiredMapString(entry, "material", "theme." + key);
            Material material = resolveBlock(rawMaterial, "theme." + key, true);
            result.add(new DungeonDefinition.WeightedMaterial(
                    material,
                    optionalMapInt(entry, "weight", 1)
            ));
        }
        return List.copyOf(result);
    }

    /** 必須の通常 Mob 一覧を相対 weight 付きで読み込みます。 */
    private @NotNull List<DungeonDefinition.WeightedMob> parseMobs(
            @NotNull ConfigurationSection encounter
    ) {
        List<DungeonDefinition.WeightedMob> result = new ArrayList<>();
        for (Map<?, ?> entry : encounter.getMapList("normalMobPool")) {
            result.add(new DungeonDefinition.WeightedMob(
                    stripPrefix(requiredMapString(entry, "mobId", "encounter.normalMobPool")),
                    optionalMapInt(entry, "level", -1) < 1
                            ? null
                            : optionalMapInt(entry, "level", -1),
                    optionalMapInt(entry, "weight", 1)
            ));
        }
        return List.copyOf(result);
    }

    private @Nullable Integer optionalNullableInt(
            @Nullable ConfigurationSection section,
            @NotNull String key
    ) {
        if (section == null || !section.contains(key)) {
            return null;
        }
        int value = section.getInt(key, -1);
        return value < 1 ? null : value;
    }

    /** min/max の部分省略を許可し、既定値で補完した範囲を返します。 */
    private @NotNull DungeonDefinition.IntRange parseRange(
            ConfigurationSection section,
            @NotNull String path,
            int defaultMin,
            int defaultMax
    ) {
        if (section == null) {
            return new DungeonDefinition.IntRange(defaultMin, defaultMax);
        }
        return new DungeonDefinition.IntRange(
                optionalInt(section, "min", defaultMin),
                optionalInt(section, "max", defaultMax)
        );
    }

    /** 親が未定義なら {@code null} のまま子セクションを解決します。 */
    private ConfigurationSection section(ConfigurationSection parent, @NotNull String key) {
        return parent == null ? null : parent.getConfigurationSection(key);
    }

    /** 省略可能な整数を読み、未定義なら既定値を返します。 */
    private int optionalInt(ConfigurationSection section, @NotNull String key, int fallback) {
        if (section == null || !section.contains(key)) {
            return fallback;
        }
        return requireInt(section, key);
    }

    /** 省略可能な数値を読み、未定義なら既定値を返します。 */
    private double optionalDouble(ConfigurationSection section, @NotNull String key, double fallback) {
        if (section == null || !section.contains(key)) {
            return fallback;
        }
        return requireDouble(section, key);
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
        double numeric = number.doubleValue();
        if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)
                || numeric < Integer.MIN_VALUE || numeric > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid integer: " + path + "." + key);
        }
        return (int) numeric;
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

    /** 省略可能な block Material を検証し、未定義なら既定値を返します。 */
    private @NotNull Material optionalBlock(
            ConfigurationSection section,
            @NotNull String key,
            @NotNull Material fallback,
            boolean solid
    ) {
        return section == null || !section.contains(key)
                ? fallback
                : requireBlock(section, key, solid);
    }

    /** 省略可能な階段 Material を検証し、未定義なら既定値を返します。 */
    private @NotNull Material optionalStairs(
            ConfigurationSection section,
            @NotNull String key,
            @NotNull Material fallback
    ) {
        return section == null || !section.contains(key)
                ? fallback
                : requireStairs(section, key);
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
            double numeric = number.doubleValue();
            if (Double.isFinite(numeric) && numeric == Math.rint(numeric)
                    && numeric >= Integer.MIN_VALUE && numeric <= Integer.MAX_VALUE) {
                return (int) numeric;
            }
        }
        throw new IllegalArgumentException("Invalid integer: " + path + "." + key);
    }

    /** map 内の省略可能な整数を読み、未定義なら既定値を返します。 */
    private int optionalMapInt(@NotNull Map<?, ?> entry, @NotNull String key, int fallback) {
        return entry.containsKey(key) ? requiredMapInt(entry, key, key) : fallback;
    }

    private double optionalMapDouble(@NotNull Map<?, ?> entry, @NotNull String key, double fallback) {
        if (!entry.containsKey(key)) return fallback;
        Object value = entry.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Invalid number: " + key);
        }
        return number.doubleValue();
    }

    private @NotNull String optionalMapAmount(
            @NotNull Map<?, ?> entry,
            @NotNull String key,
            @NotNull String fallback
    ) {
        if (!entry.containsKey(key)) return fallback;
        Object value = entry.get(key);
        if (!(value instanceof String) && !(value instanceof Number)) {
            throw new IllegalArgumentException("Invalid amount: " + key);
        }
        String amount = value.toString().trim();
        String[] parts = amount.split("~", -1);
        if (parts.length < 1 || parts.length > 2) {
            throw new IllegalArgumentException("Invalid amount: " + key);
        }
        try {
            int minimum = Integer.parseInt(parts[0]);
            int maximum = parts.length == 1 ? minimum : Integer.parseInt(parts[1]);
            if (minimum < 1 || maximum < minimum) {
                throw new IllegalArgumentException("Invalid amount: " + key);
            }
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid amount: " + key, failure);
        }
        return amount;
    }

    private String optionalString(@NotNull ConfigurationSection section, @NotNull String key) {
        String value = section.getString(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private @NotNull String stripPrefix(@NotNull String raw) {
        int separator = raw.indexOf(':');
        return separator < 0 ? raw.trim() : raw.substring(separator + 1).trim();
    }
}
