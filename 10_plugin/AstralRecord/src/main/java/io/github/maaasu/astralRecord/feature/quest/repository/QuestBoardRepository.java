package io.github.maaasu.astralRecord.feature.quest.repository;

import io.github.maaasu.astralRecord.feature.quest.model.QuestBoardDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestBoardEntry;
import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class QuestBoardRepository {
    private static final String RELATIVE_PATH = "48.features.quest_board";

    public @NotNull List<QuestBoardDefinition> findAll() {
        File root = FileDatabaseManager.getInstance().getRootDirectory();
        if (root == null) {
            return List.of();
        }
        File directory = new File(root, RELATIVE_PATH);
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            return List.of();
        }
        List<QuestBoardDefinition> result = new ArrayList<>();
        for (File file : files) {
            QuestBoardDefinition board = parse(YamlConfiguration.loadConfiguration(file));
            if (board != null) {
                result.add(board);
            }
        }
        return result;
    }

    private @Nullable QuestBoardDefinition parse(@NotNull YamlConfiguration yaml) {
        String id = yaml.getString("id");
        if (id == null || id.isBlank()) {
            return null;
        }
        List<QuestBoardEntry> entries = new ArrayList<>();
        for (Map<?, ?> map : yaml.getMapList("quests")) {
            String questId = stripPrefix(readReference(map.get("questId")));
            if (questId == null || questId.isBlank()) {
                continue;
            }
            entries.add(new QuestBoardEntry(
                questId,
                parseInt(map.get("page"), 1),
                parseOptionalInt(map.get("slot")),
                parseOptionalInt(map.get("row")),
                parseOptionalInt(map.get("column"))
            ));
        }
        return new QuestBoardDefinition(id, yaml.getString("name", id), entries);
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

    private @Nullable Integer parseOptionalInt(@Nullable Object raw) {
        return raw == null ? null : parseInt(raw, 0);
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
