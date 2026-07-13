package io.github.maaasu.astralRecord.feature.quest.repository;

import io.github.maaasu.astralRecord.feature.quest.model.QuestCompletionMode;
import io.github.maaasu.astralRecord.feature.quest.model.QuestDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestItemStackDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestObjectiveDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestObjectiveType;
import io.github.maaasu.astralRecord.feature.quest.model.QuestRepeatMode;
import io.github.maaasu.astralRecord.feature.quest.model.QuestRequirementDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestRewardDefinition;
import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class QuestDefinitionRepository {
    private static final String RELATIVE_PATH = "47.features.quest";

    public @NotNull List<QuestDefinition> findAll() {
        File root = FileDatabaseManager.getInstance().getRootDirectory();
        if (root == null) {
            return List.of();
        }
        File directory = new File(root, RELATIVE_PATH);
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            return List.of();
        }
        List<QuestDefinition> result = new ArrayList<>();
        for (File file : files) {
            QuestDefinition definition = parse(YamlConfiguration.loadConfiguration(file));
            if (definition != null) {
                result.add(definition);
            }
        }
        return result;
    }

    @Nullable QuestDefinition parse(@NotNull YamlConfiguration yaml) {
        String id = yaml.getString("id");
        if (id == null || id.isBlank()) {
            return null;
        }
        List<QuestObjectiveDefinition> objectives = parseObjectives(yaml);
        if (objectives.isEmpty()) {
            return null;
        }
        return new QuestDefinition(
            id,
            yaml.getString("name", id),
            yaml.getStringList("description"),
            parseMaterial(yaml.getString("icon"), Material.PAPER),
            QuestRepeatMode.from(yaml.getString("repeat.mode")),
            yaml.getLong("repeat.cooldownSeconds", 0L),
            QuestCompletionMode.from(yaml.getString("completion.mode")),
            stripPrefix(readReference(yaml.get("completion.turnInNpcId"))),
            objectives,
            parseRequirements(yaml),
            parseRewards(yaml.getConfigurationSection("rewards"))
        );
    }

    private @NotNull List<QuestObjectiveDefinition> parseObjectives(@NotNull YamlConfiguration yaml) {
        List<QuestObjectiveDefinition> result = new ArrayList<>();
        for (Map<?, ?> map : yaml.getMapList("objectives")) {
            QuestObjectiveType type = QuestObjectiveType.from(asString(map.get("type")));
            String targetId = stripPrefix(readReference(map.get("targetId")));
            if (type == null || targetId == null || targetId.isBlank()) {
                continue;
            }
            result.add(new QuestObjectiveDefinition(
                valueOrDefault(asString(map.get("id")), type.name().toLowerCase(Locale.ROOT) + "_" + targetId),
                type,
                targetId,
                valueOrDefault(asString(map.get("label")), targetId),
                parseInt(map.get("amount"), 1)
            ));
        }
        return result;
    }

    private @NotNull List<QuestRequirementDefinition> parseRequirements(@NotNull YamlConfiguration yaml) {
        List<QuestRequirementDefinition> result = new ArrayList<>();
        for (Map<?, ?> map : yaml.getMapList("acceptRequirements.items")) {
            QuestItemStackDefinition item = parseItem(map);
            if (!item.itemId().isBlank()) {
                result.add(new QuestRequirementDefinition(item, parseBoolean(map.get("consume"), false)));
            }
        }
        return result;
    }

    private @NotNull QuestRewardDefinition parseRewards(@Nullable ConfigurationSection section) {
        if (section == null) {
            return new QuestRewardDefinition(0, 0L, List.of());
        }
        List<QuestItemStackDefinition> items = new ArrayList<>();
        for (Map<?, ?> map : section.getMapList("items")) {
            QuestItemStackDefinition item = parseItem(map);
            if (!item.itemId().isBlank()) {
                items.add(item);
            }
        }
        return new QuestRewardDefinition(section.getInt("exp", 0), section.getLong("gold", 0L), items);
    }

    private @NotNull QuestItemStackDefinition parseItem(@NotNull Map<?, ?> map) {
        String itemId = stripPrefix(readReference(map.get("itemId")));
        return new QuestItemStackDefinition(
            itemId == null ? "" : itemId,
            valueOrDefault(asString(map.get("category")), "material"),
            parseInt(map.get("amount"), 1)
        );
    }

    private @Nullable String readReference(@Nullable Object raw) {
        if (raw instanceof ConfigurationSection section) {
            return section.getString("ref");
        }
        if (raw instanceof Map<?, ?> map) {
            Object ref = map.get("ref");
            return ref == null ? null : ref.toString();
        }
        return raw == null ? null : raw.toString();
    }

    private @Nullable String stripPrefix(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        int index = trimmed.indexOf(':');
        return index < 0 ? trimmed : trimmed.substring(index + 1).trim();
    }

    private @NotNull Material parseMaterial(@Nullable String raw, @NotNull Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Material.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private @Nullable String asString(@Nullable Object raw) {
        return raw == null ? null : raw.toString();
    }

    private @NotNull String valueOrDefault(@Nullable String value, @NotNull String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private boolean parseBoolean(@Nullable Object raw, boolean fallback) {
        return raw == null ? fallback : Boolean.parseBoolean(raw.toString());
    }

    private int parseInt(@Nullable Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return raw == null ? fallback : Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
