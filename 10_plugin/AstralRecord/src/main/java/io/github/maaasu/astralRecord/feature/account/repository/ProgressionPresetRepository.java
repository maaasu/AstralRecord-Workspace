package io.github.maaasu.astralRecord.feature.account.repository;

import io.github.maaasu.astralRecord.feature.account.model.ProgressionPreset;
import io.github.maaasu.astralRecord.feature.account.model.ProgressionPreset.Checkpoint;
import io.github.maaasu.astralRecord.feature.account.model.ProgressionPreset.Equipment;
import io.github.maaasu.astralRecord.feature.account.model.ProgressionPreset.Sigil;
import io.github.maaasu.astralRecord.feature.account.model.ProgressionPreset.SkillBinding;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.infrastructure.api.RuntimeFilebaseSnapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 戦闘検証用プリセットを実行用 Filebase から読み込みます。 */
public final class ProgressionPresetRepository {
    private static final String DIRECTORY = "36.features.progression_preset";
    private static final Pattern ID = Pattern.compile("[a-z0-9]+(?:_[a-z0-9]+)*");
    private static final Set<String> EQUIPMENT_SLOTS = Set.of("MAIN_HAND", "OFF_HAND", "HEAD", "CHEST", "LEGS", "FEET");

    private ProgressionPresetRepository() { }

    /**
     * 原稿の構造と一意性を検証し、公開可能な不変リストを返します。
     * @param snapshot APIから取得した実行用マスタ
     * @return ファイル順のプリセット一覧
     * @throws IllegalArgumentException 原稿の構造やIDが不正な場合
     */
    public static List<ProgressionPreset> load(RuntimeFilebaseSnapshot snapshot) {
        List<ProgressionPreset> presets = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> builds = new HashSet<>();
        for (var entry : snapshot.files(DIRECTORY)) {
            try {
                ProgressionPreset preset = parse(entry.yaml().getValues(false));
                require(entry.name().equals("v1." + preset.id() + ".yml"), "file name does not match id");
                require(ids.add(preset.id()), "duplicate id: " + preset.id());
                require(builds.add(preset.classId() + ":" + preset.buildId()), "duplicate classId and buildId");
                presets.add(preset);
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("Invalid progression preset: " + entry.path() + ": " + failure.getMessage(), failure);
            }
        }
        return List.copyOf(presets);
    }

    private static ProgressionPreset parse(Map<String, Object> yaml) {
        fields(yaml, Set.of("schemaVersion", "type", "id", "name", "classId", "buildId", "levels"), Set.of());
        require(number(yaml.get("schemaVersion"), "schemaVersion") == 1, "schemaVersion must be 1");
        require("PROGRESSION_PRESET".equals(yaml.get("type")), "invalid type");
        String id = id(yaml, "id");
        String name = string(yaml.get("name"), "name");
        String classId = id(yaml, "classId");
        String buildId = id(yaml, "buildId");
        require(!"administrator".equals(classId), "administrator class is unavailable");

        List<Checkpoint> checkpoints = new ArrayList<>();
        int previous = 0;
        for (Object value : list(yaml.get("levels"), "levels")) {
            Checkpoint checkpoint = parseCheckpoint(map(value, "levels"));
            require(checkpoint.level() > previous && checkpoint.level() <= ProgressionPreset.MAX_LEVEL,
                "levels must be unique and ascending from 1 to 50");
            checkpoints.add(checkpoint);
            previous = checkpoint.level();
        }
        require(!checkpoints.isEmpty() && checkpoints.getFirst().level() == ProgressionPreset.MIN_LEVEL,
            "levels must start at 1");
        return new ProgressionPreset(id, name, classId, buildId, checkpoints);
    }

    private static Checkpoint parseCheckpoint(Map<?, ?> row) {
        fields(row, Set.of("level", "classLevel", "nodes", "equipment", "skills"), Set.of("passives"));
        int level = number(row.get("level"), "level");
        int classLevel = number(row.get("classLevel"), "classLevel");
        List<Integer> nodes = list(row.get("nodes"), "nodes").stream().map(value -> number(value, "nodes")).toList();
        require(new HashSet<>(nodes).size() == nodes.size(), "duplicate node");

        Set<String> equipmentSlots = new HashSet<>();
        List<Equipment> equipment = new ArrayList<>();
        for (Object value : list(row.get("equipment"), "equipment")) {
            Map<?, ?> entry = map(value, "equipment");
            fields(entry, Set.of("slot", "item"), Set.of("enhanceLevel"));
            String slot = string(entry.get("slot"), "equipment.slot");
            require(EQUIPMENT_SLOTS.contains(slot) && equipmentSlots.add(slot), "invalid or duplicate equipment slot: " + slot);
            int enhanceLevel = entry.containsKey("enhanceLevel")
                ? integer(entry.get("enhanceLevel"), "equipment.enhanceLevel", 0) : 0;
            equipment.add(new Equipment(slot, reference(entry, "item"), enhanceLevel));
        }
        require(equipmentSlots.contains("MAIN_HAND"), "MAIN_HAND equipment is required");
        List<SkillBinding> skills = bindings(row.get("skills"), "skills", SkillBindPreset.ACTION_RING_SLOT_COUNT);
        require(skills.stream().anyMatch(binding -> binding.slot() == 1), "skills must assign slot 1");
        List<SkillBinding> passives = row.containsKey("passives")
            ? bindings(row.get("passives"), "passives", SkillBindPreset.PASSIVE_SLOT_COUNT) : List.of();
        return new Checkpoint(level, classLevel, nodes, equipment, skills, passives);
    }

    private static List<SkillBinding> bindings(Object value, String key, int max) {
        Set<Integer> slots = new HashSet<>();
        Set<String> skills = new HashSet<>();
        List<SkillBinding> result = new ArrayList<>();
        for (Object entry : list(value, key)) {
            Map<?, ?> binding = map(entry, key);
            fields(binding, Set.of("slot", "skill"), Set.of("skillLevel", "sigils"));
            int slot = number(binding.get("slot"), key + ".slot");
            String skill = reference(binding, "skill");
            require(slot <= max && slots.add(slot), "invalid or duplicate " + key + " slot: " + slot);
            require(skills.add(skill), "duplicate " + key + " skill: " + skill);
            int skillLevel = binding.containsKey("skillLevel")
                ? number(binding.get("skillLevel"), key + ".skillLevel") : 1;
            List<Sigil> sigils = binding.containsKey("sigils") ? sigils(binding.get("sigils"), key) : List.of();
            result.add(new SkillBinding(slot, skill, skillLevel, sigils));
        }
        return result;
    }

    private static List<Sigil> sigils(Object value, String key) {
        String field = key + ".sigils";
        Set<Integer> slots = new HashSet<>();
        List<Sigil> result = new ArrayList<>();
        for (Object element : list(value, field)) {
            Map<?, ?> sigil = map(element, field);
            fields(sigil, Set.of("slot", "item"), Set.of());
            int slot = number(sigil.get("slot"), field + ".slot");
            require(slots.add(slot), "duplicate " + field + " slot: " + slot);
            result.add(new Sigil(slot, reference(sigil, "item")));
        }
        return result;
    }

    private static void fields(Map<?, ?> value, Set<String> required, Set<String> optional) {
        require(value.keySet().containsAll(required), "required fields: " + required);
        for (Object key : value.keySet()) require(required.contains(key) || optional.contains(key), "unknown field: " + key);
    }

    private static String id(Map<?, ?> value, String key) {
        String result = string(value.get(key), key);
        require(ID.matcher(result).matches(), "invalid " + key + ": " + result);
        return result;
    }

    private static String reference(Map<?, ?> value, String key) {
        String result = string(value.get(key), key);
        String prefix = key + ":";
        require(result.startsWith(prefix) && ID.matcher(result.substring(prefix.length())).matches(),
            "invalid " + key + " reference: " + result);
        return result;
    }

    private static int number(Object value, String field) {
        return integer(value, field, 1);
    }

    private static int integer(Object value, String field, int minimum) {
        if (value instanceof Number number) {
            int integer = number.intValue();
            if (integer >= minimum && number.doubleValue() == integer) return integer;
        }
        throw new IllegalArgumentException(field + " must be an integer >= " + minimum);
    }

    private static String string(Object value, String field) {
        if (value instanceof String text && !text.isBlank()) return text;
        throw new IllegalArgumentException(field + " must be a non-blank string");
    }

    private static List<?> list(Object value, String field) {
        if (value instanceof List<?> list) return list;
        throw new IllegalArgumentException(field + " must be a list");
    }

    private static Map<?, ?> map(Object value, String field) {
        if (value instanceof Map<?, ?> map) return map;
        throw new IllegalArgumentException(field + " must be a mapping");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
