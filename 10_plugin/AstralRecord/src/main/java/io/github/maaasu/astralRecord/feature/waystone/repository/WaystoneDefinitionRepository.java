package io.github.maaasu.astralRecord.feature.waystone.repository;

import io.github.maaasu.astralRecord.feature.waystone.model.WaystoneDefinition;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * plugin data folder の waystones.yml を読み書きする repository です。
 */
public final class WaystoneDefinitionRepository {
    private static final String FILE_NAME = "waystones.yml";

    private final Plugin plugin;
    private final File file;

    /**
     * repository を初期化します。
     *
     * @param plugin プラグイン本体
     */
    public WaystoneDefinitionRepository(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
    }

    /**
     * 定義ファイルからウェイストーン一覧を読み込みます。
     *
     * @return 読み込んだウェイストーン定義一覧
     */
    public @NotNull List<WaystoneDefinition> loadAll() {
        if (!file.exists()) {
            return List.of();
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("waystones");
        if (root == null) {
            return List.of();
        }

        List<WaystoneDefinition> result = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            String name = section.getString("name", id);
            String world = section.getString("world", "");
            if (world.isBlank()) {
                continue;
            }
            result.add(new WaystoneDefinition(
                id,
                name,
                world,
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                (float) section.getDouble("yaw"),
                (float) section.getDouble("pitch"),
                section.getBoolean("alwaysUnlocked", false),
                Math.max(0L, section.getLong("unlockGoldCost", 100L))
            ));
        }
        result.sort(Comparator.comparing(WaystoneDefinition::worldName).thenComparing(WaystoneDefinition::name));
        return result;
    }

    /**
     * 現在地を元に新しいウェイストーン定義を作成し、ファイルへ保存します。
     *
     * @param name 表示名
     * @param location 配置位置
     * @param alwaysUnlocked 常時開放フラグ
     * @param unlockGoldCost 初回開放に必要なゴールド
     * @return 作成したウェイストーン定義
     * @throws IOException ファイル保存に失敗した場合
     */
    public @NotNull WaystoneDefinition create(
        @NotNull String name,
        @NotNull Location location,
        boolean alwaysUnlocked,
        long unlockGoldCost
    ) throws IOException {
        if (location.getWorld() == null) {
            throw new IOException("World is not available.");
        }

        plugin.getDataFolder().mkdirs();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String id;
        do {
            id = UUID.randomUUID().toString();
        } while (yaml.contains("waystones." + id));

        String path = "waystones." + id;
        yaml.set(path + ".name", name);
        yaml.set(path + ".world", location.getWorld().getName());
        yaml.set(path + ".x", location.getX());
        yaml.set(path + ".y", location.getY());
        yaml.set(path + ".z", location.getZ());
        yaml.set(path + ".yaw", location.getYaw());
        yaml.set(path + ".pitch", location.getPitch());
        yaml.set(path + ".alwaysUnlocked", alwaysUnlocked);
        yaml.set(path + ".unlockGoldCost", Math.max(0L, unlockGoldCost));
        yaml.save(file);

        return new WaystoneDefinition(
            id,
            name,
            location.getWorld().getName(),
            location.getX(),
            location.getY(),
            location.getZ(),
            location.getYaw(),
            location.getPitch(),
            alwaysUnlocked,
            Math.max(0L, unlockGoldCost)
        );
    }
}
