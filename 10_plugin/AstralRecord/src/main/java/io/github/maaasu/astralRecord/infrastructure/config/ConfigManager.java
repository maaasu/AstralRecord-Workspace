package io.github.maaasu.astralRecord.infrastructure.config;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * config.yml の読み込み、新規作成、リロードを管理するユーティリティクラス。
 * - プラグイン起動時に config.yml が存在しない場合は自動生成
 * - 設定値の取得、デフォルト値の提供
 * - リロード機能
 */
public final class ConfigManager {

    private static ConfigManager instance;
    private FileConfiguration config;
    private File configFile;

    private ConfigManager() {}

    /*
     * プラグインのインスタンスを取得します。
     */
    public static synchronized ConfigManager getInstance() {
        if (instance == null) {
            instance = new ConfigManager();
        }
        return instance;
    }

    /**
     * 設定ファイルを初期化します。
     * - ファイルが存在しない場合は resources/config.yml からコピー
     * - 存在する場合は既存ファイルを読み込み
     */
    public void initialize() {
        Plugin plugin = AstralRecord.getInstance();
        File dataFolder = plugin.getDataFolder();
        this.configFile = new File(dataFolder, "config.yml");

        // プラグインのデータフォルダを作成
        if (!dataFolder.exists()) {
            boolean created = dataFolder.mkdirs();
            //if (created)
                //Logger.log(LogId.DATA_FOLDER_CREATED, dataFolder.getPath());
            //else
                //Logger.log(LogId.DATA_FOLDER_CREATE_FAILED);
        }

        // config.yml が存在しない場合は新規作成
        if (!configFile.exists()) {
            createDefaultConfig();
        }

        // 設定をロード
        loadConfig();

        // ConfigPropertiesを初期化
        ConfigProperties.getInstance().initialize();

        //Logger.log(LogId.CONFIG_LOADED);
    }

    /**
     * デフォルトの config.yml を resources からコピーして作成します。
     */
    private void createDefaultConfig() {
        Plugin plugin = AstralRecord.getInstance();
        try (InputStream inputStream = plugin.getResource("config.yml")) {
            if (inputStream == null) {
                //Logger.log(LogId.CONFIG_RESOURCE_NOT_FOUND);
                return;
            }

            // リソースファイルをプラグインフォルダにコピー
            Files.copy(inputStream, configFile.toPath());
            //Logger.log(LogId.CONFIG_DEFAULT_CREATED, configFile.getPath());

        } catch (IOException e) {
            //Logger.log(LogId.CONFIG_CREATE_FAILED, e);
            //throw new RuntimeException(LogId.CONFIG_INITIALIZATION_FAILED.getId(), e);
        }
    }

    /**
     * 設定ファイルを読み込みます。
     */
    private void loadConfig() {
        this.config = YamlConfiguration.loadConfiguration(configFile);

        // デフォルト設定をマージ（resources/config.yml の値をデフォルトとして設定）
        Plugin plugin = AstralRecord.getInstance();
        try (InputStream defConfigStream = plugin.getResource("config.yml")) {
            if (defConfigStream == null) return; // リソースが存在しない場合は何もしない (nullチェックは不要)

            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defConfigStream, StandardCharsets.UTF_8)
            );
            config.setDefaults(defConfig);
        } catch (IOException e) {
            //Logger.warn(LogId.CONFIG_DEFAULT_LOAD_FAILED, e.getMessage());
        }
    }

    /**
     * 設定をリロードします。
     */
    public void reload() {
        loadConfig();
        //Logger.log(LogId.CONFIG_RELOADED);
    }

    /**
     * 設定ファイルを保存します。
     */
    public void save() {
        try {
            config.save(configFile);
            //Logger.log(LogId.CONFIG_SAVED);
        } catch (IOException e) {
            //Logger.log(LogId.CONFIG_SAVE_FAILED, e);
        }
    }

    /**
     * FileConfiguration を取得します。
     */
    public FileConfiguration getConfig() {
        return config;
    }

    /**
     * 文字列値を取得します。
     */
    public String getString(String path, String defaultValue) {
        return config.getString(path, defaultValue);
    }

    /**
     * 整数値を取得します。
     */
    public int getInt(String path, int defaultValue) {
        return config.getInt(path, defaultValue);
    }

    /**
     * 長整数値を取得します。
     */
    public long getLong(String path, long defaultValue) {
        return config.getLong(path, defaultValue);
    }

    /**
     * 真偽値を取得します。
     */
    public boolean getBoolean(String path, boolean defaultValue) {
        return config.getBoolean(path, defaultValue);
    }

    /**
     * 浮動小数点数を取得します。
     */
    public double getDouble(String path, double defaultValue) {
        return config.getDouble(path, defaultValue);
    }

    /**
     * 設定値を設定します。
     */
    public void set(String path, Object value) {
        config.set(path, value);
    }

    /**
     * 設定ファイルのパスを取得します。
     */
    public File getConfigFile() {
        return configFile;
    }
}
