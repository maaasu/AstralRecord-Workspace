package io.github.maaasu.astralRecord.feature.skilltree.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeEffect;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePointType;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeSkillEffect;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeStatusEffect;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeUnlockCondition;
import io.github.maaasu.astralRecord.feature.status.model.StatusModifierType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager;
import io.github.maaasu.astralRecord.infrastructure.util.MaterialNameResolver;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** filebase のJSONスキルツリーノード定義を読み込むリポジトリです。 */
public class SkillTreeNodeRepository {
    private static final String RELATIVE_PATH = "35.features.skilltree" + File.separator + "nodes";
    private static final String SCHEMA_REFERENCE = "../schemas/node.v1.schema.json";
    private static final Set<String> NODE_KEYS = Set.of(
            "$schema", "schemaVersion", "nodeId", "name", "icon", "lore", "tags",
            "pointType", "pointCost", "unlockCondition", "effects"
    );
    private static final Set<String> UNLOCK_CONDITION_KEYS = Set.of("classId", "playerLevel");
    private static final Set<String> SKILL_EFFECT_KEYS = Set.of("type", "skillId");
    private static final Set<String> STATUS_EFFECT_KEYS = Set.of(
            "type", "status", "modifierType", "value"
    );

    private final File fixedRootDirectory;
    private final Predicate<Material> usableIconPredicate;

    public SkillTreeNodeRepository() {
        this.fixedRootDirectory = null;
        this.usableIconPredicate = material -> material != Material.AIR && material.isItem();
    }

    SkillTreeNodeRepository(@NotNull File rootDirectory) {
        this(rootDirectory, material -> material != Material.AIR && material.isItem());
    }

    SkillTreeNodeRepository(
            @NotNull File rootDirectory,
            @NotNull Predicate<Material> usableIconPredicate
    ) {
        this.fixedRootDirectory = rootDirectory;
        this.usableIconPredicate = usableIconPredicate;
    }

    /**
     * ノードJSONをすべて読み込み、ファイル名順の不変スナップショットを返します。
     *
     * @return 検証済みノード定義
     * @throws IllegalStateException ディレクトリ、JSON、IDまたは効果定義が不正な場合
     */
    public @NotNull List<SkillTreeNodeDefinition> findAll() {
        File root = fixedRootDirectory == null
                ? FileDatabaseManager.getInstance().getRootDirectory()
                : fixedRootDirectory;
        if (root == null) {
            throw new IllegalStateException("filebase root is not configured");
        }
        File directory = new File(root, RELATIVE_PATH);
        File[] files = directory.listFiles((ignored, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (files == null) {
            throw new IllegalStateException("Skilltree node directory is missing: " + directory.getAbsolutePath());
        }
        Arrays.sort(files, Comparator.comparing(File::getName));

        Map<String, SkillTreeNodeDefinition> definitions = new LinkedHashMap<>();
        Map<String, String> skillPermissionNodes = new LinkedHashMap<>();
        for (File file : files) {
            SkillTreeNodeDefinition definition = parse(file);
            if (definitions.putIfAbsent(definition.nodeId(), definition) != null) {
                throw SkillTreeJsonReader.invalid(file, "duplicate nodeId '" + definition.nodeId() + "'");
            }
            validateUniqueSkillPermissions(definition, file, skillPermissionNodes);
        }
        return List.copyOf(definitions.values());
    }

    private void validateUniqueSkillPermissions(
            @NotNull SkillTreeNodeDefinition definition,
            @NotNull File file,
            @NotNull Map<String, String> skillPermissionNodes
    ) {
        String classId = definition.unlockCondition().classId();
        String normalizedClassId = classId == null ? "" : classId.trim().toLowerCase(Locale.ROOT);
        for (SkillTreeSkillEffect effect : definition.skillEffects()) {
            String normalizedSkillId = effect.skillId().trim().toLowerCase(Locale.ROOT);
            String key = normalizedClassId + '\0' + normalizedSkillId;
            String previousNodeId = skillPermissionNodes.putIfAbsent(key, definition.nodeId());
            if (previousNodeId != null) {
                String condition = normalizedClassId.isEmpty() ? "without a class condition" : "for class '" + normalizedClassId + "'";
                throw SkillTreeJsonReader.invalid(
                        file,
                        "duplicate skill permission '" + normalizedSkillId + "' " + condition
                                + " in nodes '" + previousNodeId + "' and '" + definition.nodeId() + "'"
                );
            }
        }
    }

    private @NotNull SkillTreeNodeDefinition parse(@NotNull File file) {
        JsonObject node = SkillTreeJsonReader.readObject(file);
        SkillTreeJsonReader.requireOnlyKeys(node, NODE_KEYS, file, "node");
        String schemaReference = SkillTreeJsonReader.requiredString(node, "$schema", file, "node");
        if (!SCHEMA_REFERENCE.equals(schemaReference)) {
            throw SkillTreeJsonReader.invalid(file, "unsupported node.$schema '" + schemaReference + "'");
        }
        int schemaVersion = SkillTreeJsonReader.requiredInt(node, "schemaVersion", file, "node");
        if (schemaVersion != 1) {
            throw SkillTreeJsonReader.invalid(file, "unsupported schemaVersion " + schemaVersion);
        }
        String nodeId = digitId(node, "nodeId", file, "node");
        String name = SkillTreeJsonReader.requiredString(node, "name", file, "node");
        Material icon = parseMaterial(SkillTreeJsonReader.requiredString(node, "icon", file, "node"), file);
        List<String> lore = stringArray(node, "lore", file);
        List<String> tags = tags(node, file);
        SkillTreePointType pointType = parsePointType(
                SkillTreeJsonReader.requiredString(node, "pointType", file, "node"),
                file
        );
        int pointCost = SkillTreeJsonReader.requiredInt(node, "pointCost", file, "node");
        if (pointCost < 0) {
            throw SkillTreeJsonReader.invalid(file, "node.pointCost must be zero or greater");
        }
        return new SkillTreeNodeDefinition(
                nodeId,
                name,
                icon,
                lore,
                tags,
                pointType,
                pointCost,
                parseUnlockCondition(node, file),
                parseEffects(node, file)
        );
    }

    private @NotNull SkillTreeUnlockCondition parseUnlockCondition(@NotNull JsonObject node, @NotNull File file) {
        if (!node.has("unlockCondition") || node.get("unlockCondition").isJsonNull()) {
            return SkillTreeUnlockCondition.NONE;
        }
        JsonObject condition = SkillTreeJsonReader.requiredObject(
                node.get("unlockCondition"),
                file,
                "node.unlockCondition"
        );
        SkillTreeJsonReader.requireOnlyKeys(condition, UNLOCK_CONDITION_KEYS, file, "node.unlockCondition");
        String classId = null;
        if (condition.has("classId") && !condition.get("classId").isJsonNull()) {
            classId = SkillTreeJsonReader.requiredString(
                    condition,
                    "classId",
                    file,
                    "node.unlockCondition"
            ).trim();
            if (classId.length() > 100 || !classId.matches("[a-z0-9][a-z0-9_-]*")) {
                throw SkillTreeJsonReader.invalid(file, "node.unlockCondition.classId has an invalid format");
            }
        }
        int playerLevel = 0;
        if (condition.has("playerLevel") && !condition.get("playerLevel").isJsonNull()) {
            playerLevel = SkillTreeJsonReader.requiredInt(
                    condition,
                    "playerLevel",
                    file,
                    "node.unlockCondition"
            );
            if (playerLevel < 1) {
                throw SkillTreeJsonReader.invalid(file, "node.unlockCondition.playerLevel must be 1 or greater");
            }
        }
        if (classId == null && playerLevel == 0) {
            throw SkillTreeJsonReader.invalid(file, "node.unlockCondition must define classId or playerLevel");
        }
        return new SkillTreeUnlockCondition(classId, playerLevel);
    }

    private @NotNull List<SkillTreeNodeEffect> parseEffects(JsonObject node, File file) {
        JsonArray effects = SkillTreeJsonReader.requiredArray(node, "effects", file, "node");
        List<SkillTreeNodeEffect> result = new ArrayList<>();
        for (int index = 0; index < effects.size(); index++) {
            String path = "node.effects[" + index + "]";
            JsonObject effect = SkillTreeJsonReader.requiredObject(effects.get(index), file, path);
            String type = SkillTreeJsonReader.requiredString(effect, "type", file, path);
            switch (type) {
                case "skill" -> {
                    SkillTreeJsonReader.requireOnlyKeys(effect, SKILL_EFFECT_KEYS, file, path);
                    result.add(new SkillTreeSkillEffect(
                            SkillTreeJsonReader.requiredString(effect, "skillId", file, path).trim()
                    ));
                }
                case "status" -> {
                    SkillTreeJsonReader.requireOnlyKeys(effect, STATUS_EFFECT_KEYS, file, path);
                    result.add(parseStatusEffect(effect, file, path));
                }
                default -> throw SkillTreeJsonReader.invalid(file, path + ".type is unsupported: " + type);
            }
        }
        return List.copyOf(result);
    }

    private @NotNull SkillTreeStatusEffect parseStatusEffect(JsonObject effect, File file, String path) {
        String rawStatus = SkillTreeJsonReader.requiredString(effect, "status", file, path);
        String rawModifier = SkillTreeJsonReader.requiredString(effect, "modifierType", file, path);
        try {
            return new SkillTreeStatusEffect(
                    StatusType.valueOf(rawStatus),
                    StatusModifierType.valueOf(rawModifier),
                    SkillTreeJsonReader.requiredDouble(effect, "value", file, path)
            );
        } catch (IllegalArgumentException e) {
            throw SkillTreeJsonReader.invalid(file, path + " contains an unknown status or modifierType");
        }
    }

    private @NotNull List<String> stringArray(JsonObject object, String key, File file) {
        JsonArray values = SkillTreeJsonReader.requiredArray(object, key, file, "node");
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            JsonElement element = values.get(index);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw SkillTreeJsonReader.invalid(file, "node." + key + "[" + index + "] must be a string");
            }
            result.add(element.getAsString());
        }
        return List.copyOf(result);
    }

    private @NotNull List<String> tags(JsonObject object, File file) {
        List<String> tags = stringArray(object, "tags", file);
        Set<String> unique = new java.util.LinkedHashSet<>();
        for (int index = 0; index < tags.size(); index++) {
            String tag = tags.get(index);
            if (tag.isBlank()) {
                throw SkillTreeJsonReader.invalid(file, "node.tags[" + index + "] must not be blank");
            }
            if (!unique.add(tag)) {
                throw SkillTreeJsonReader.invalid(file, "node.tags contains duplicate value '" + tag + "'");
            }
        }
        return tags;
    }

    private @NotNull String digitId(JsonObject object, String key, File file, String path) {
        String value = SkillTreeJsonReader.requiredString(object, key, file, path).trim();
        if (value.length() > 100 || !value.matches("0|[1-9][0-9]*")) {
            throw SkillTreeJsonReader.invalid(
                    file,
                    path + "." + key + " must be '0' or a non-zero-leading digit string of at most 100 characters"
            );
        }
        return value;
    }

    private @NotNull Material parseMaterial(String raw, File file) {
        Material material = MaterialNameResolver.match(raw);
        if (material == null || !usableIconPredicate.test(material)) {
            throw SkillTreeJsonReader.invalid(file, "node.icon is not a usable item material: " + raw);
        }
        return material;
    }

    private @NotNull SkillTreePointType parsePointType(String raw, File file) {
        return switch (raw) {
            case "CP" -> SkillTreePointType.CLASS_POINT;
            case "PP" -> SkillTreePointType.PASSIVE_POINT;
            default -> throw SkillTreeJsonReader.invalid(file, "node.pointType is unsupported: " + raw);
        };
    }
}
