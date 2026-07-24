package io.github.maaasu.astralRecord.feature.skilltree.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;

/**
 * config.yml で選択するスキルツリー構造と配置中心を表す不変設定です。
 *
 * @param worldName ノードを表示する Bukkit ワールド名またはワールドフォルダパス
 * @param structureId 読み込む構造 ID
 * @param center 相対座標の原点となる絶対ブロック座標
 */
public record SkillTreePluginConfig(
        @NotNull String worldName,
        @NotNull String structureId,
        @NotNull Center center
) {
    public static final String DEFAULT_WORLD_NAME = "plugins/AstralRecord/worlds/hub/skill_tree";
    public static final String DEFAULT_STRUCTURE_ID = "starter";
    public static final Center DEFAULT_CENTER = new Center(1000, 65, 1000);

    public SkillTreePluginConfig {
        if (worldName.isBlank()) {
            throw new IllegalArgumentException("skilltree.worldName must not be blank");
        }
        if (!structureId.matches("[a-z0-9][a-z0-9_-]*")) {
            throw new IllegalArgumentException(
                    "skilltree.structureId must start with a lowercase letter or digit and contain only lowercase letters, digits, '_' or '-'"
            );
        }
    }

    /**
     * 読込済み plugin config からスキルツリー設定を構築します。
     *
     * @param config 読込済み config.yml
     * @return 検証済みスキルツリー設定
     * @throws IllegalArgumentException worldName または structureId が不正な場合
     */
    public static @NotNull SkillTreePluginConfig load(@NotNull FileConfiguration config) {
        String worldName = config.getString("skilltree.worldName", DEFAULT_WORLD_NAME);
        String structureId = config.getString("skilltree.structureId", DEFAULT_STRUCTURE_ID);
        return new SkillTreePluginConfig(
                worldName == null ? DEFAULT_WORLD_NAME : worldName.trim(),
                structureId == null ? DEFAULT_STRUCTURE_ID : structureId.trim(),
                new Center(
                        config.getInt("skilltree.center.x", DEFAULT_CENTER.x()),
                        config.getInt("skilltree.center.y", DEFAULT_CENTER.y()),
                        config.getInt("skilltree.center.z", DEFAULT_CENTER.z())
                )
        );
    }

    /**
     * ディスク上の config.yml を共有設定キャッシュへ反映せずに読み込みます。
     * マスタリロードの準備中に編集ツールが保存した最新値を参照するための読取専用経路です。
     *
     * @param configFile 読み込む config.yml
     * @return ファイル時点の検証済みスキルツリー設定
     * @throws IllegalStateException ファイルが存在しない、読めない、またはYAMLが不正な場合
     */
    public static @NotNull SkillTreePluginConfig loadFile(@NotNull File configFile) {
        if (!configFile.isFile()) {
            throw new IllegalStateException("Plugin config is missing: " + configFile.getAbsolutePath());
        }
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException("Failed to read plugin config: " + configFile.getAbsolutePath(), e);
        }
        return load(config);
    }

    /**
     * スキルツリー構造JSONの相対座標原点です。
     *
     * @param x X中心座標
     * @param y Y中心座標
     * @param z Z中心座標
     */
    public record Center(int x, int y, int z) {
    }
}
