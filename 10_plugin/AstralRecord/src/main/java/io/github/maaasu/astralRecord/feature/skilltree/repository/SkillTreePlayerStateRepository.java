package io.github.maaasu.astralRecord.feature.skilltree.repository;

import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePlayerState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * プレイヤー別のスキルツリー状態を plugin data に保存するリポジトリです。
 */
public class SkillTreePlayerStateRepository {
    private static final String FILE_NAME = "skilltree_players.yml";

    private final Plugin plugin;

    public SkillTreePlayerStateRepository(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    @NotNull
    public SkillTreePlayerState load(@NotNull UUID accountId) {
        YamlConfiguration yaml = loadYaml();
        String path = "accounts." + accountId;
        return new SkillTreePlayerState(
                accountId,
                yaml.getInt(path + ".skillPoints", 0),
                new LinkedHashSet<>(yaml.getStringList(path + ".unlockedNodes"))
        );
    }

    public void save(@NotNull SkillTreePlayerState state) {
        YamlConfiguration yaml = loadYaml();
        String path = "accounts." + state.accountId();
        yaml.set(path + ".skillPoints", state.skillPoints());
        yaml.set(path + ".unlockedNodes", state.unlockedNodeIds().stream().sorted().toList());
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
    private YamlConfiguration loadYaml() {
        File file = file();
        return file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    @NotNull
    private File file() {
        return new File(plugin.getDataFolder(), FILE_NAME);
    }
}
