package io.github.maaasu.astralRecord.feature.quest.repository;

import io.github.maaasu.astralRecord.feature.quest.model.QuestPlayerState;
import io.github.maaasu.astralRecord.feature.quest.model.QuestProgress;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class QuestPlayerStateRepository {
    private final File directory;

    public QuestPlayerStateRepository(@NotNull Plugin plugin) {
        this.directory = new File(plugin.getDataFolder(), "quest-states");
    }

    public @NotNull QuestPlayerState load(@NotNull UUID accountId) {
        File file = file(accountId);
        if (!file.exists()) {
            return new QuestPlayerState(accountId, Map.of(), Map.of(), Map.of());
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Map<String, QuestProgress> active = new LinkedHashMap<>();
        ConfigurationSection activeSection = yaml.getConfigurationSection("active");
        if (activeSection != null) {
            for (String questId : activeSection.getKeys(false)) {
                ConfigurationSection questSection = activeSection.getConfigurationSection(questId);
                if (questSection == null) {
                    continue;
                }
                Map<String, Integer> objectives = new LinkedHashMap<>();
                ConfigurationSection objectivesSection = questSection.getConfigurationSection("objectives");
                if (objectivesSection != null) {
                    for (String objectiveId : objectivesSection.getKeys(false)) {
                        objectives.put(objectiveId, objectivesSection.getInt(objectiveId, 0));
                    }
                }
                active.put(questId, new QuestProgress(
                    questId,
                    questSection.getLong("acceptedAt", System.currentTimeMillis()),
                    questSection.getString("acceptedNpcId"),
                    objectives,
                    questSection.getBoolean("readyToTurnIn", false)
                ));
            }
        }
        return new QuestPlayerState(
            accountId,
            active,
            readLongMap(yaml.getConfigurationSection("completedAt")),
            readLongMap(yaml.getConfigurationSection("cooldownUntil"))
        );
    }

    public void save(@NotNull QuestPlayerState state) {
        if (!directory.exists() && !directory.mkdirs()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (QuestProgress progress : state.activeQuests().values()) {
            String path = "active." + progress.questId();
            yaml.set(path + ".acceptedAt", progress.acceptedAtEpochMillis());
            yaml.set(path + ".acceptedNpcId", progress.acceptedNpcId());
            yaml.set(path + ".readyToTurnIn", progress.readyToTurnIn());
            for (Map.Entry<String, Integer> entry : progress.objectiveProgress().entrySet()) {
                yaml.set(path + ".objectives." + entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<String, Long> entry : state.completedAt().entrySet()) {
            yaml.set("completedAt." + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Long> entry : state.cooldownUntil().entrySet()) {
            yaml.set("cooldownUntil." + entry.getKey(), entry.getValue());
        }
        try {
            yaml.save(file(state.accountId()));
        } catch (IOException ignored) {
        }
    }

    private @NotNull Map<String, Long> readLongMap(ConfigurationSection section) {
        if (section == null) {
            return Map.of();
        }
        Map<String, Long> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            result.put(key, section.getLong(key, 0L));
        }
        return result;
    }

    private @NotNull File file(@NotNull UUID accountId) {
        return new File(directory, accountId + ".yml");
    }
}
