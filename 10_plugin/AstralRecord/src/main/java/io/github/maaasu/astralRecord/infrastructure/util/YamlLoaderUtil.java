package io.github.maaasu.astralRecord.infrastructure.util;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * YAMLファイルのロードを行うユーティリティクラス
 */
public final class YamlLoaderUtil {

    private YamlLoaderUtil() {
        // インスタンス化を禁止
    }

    /**
     * 指定されたファイルからYAMLをロードします
     * @param file ロードするファイル
     * @return YamlConfiguration（ロード失敗時はnull）
     */
    public static YamlConfiguration load(File file) {
        if (!file.exists()) {
            Logger.log(LogId.W_1000, file.getAbsolutePath());
            return null;
        }
        try {
            return YamlConfiguration.loadConfiguration(file);
        } catch (Exception e) {
            Logger.log(LogId.E_1000, e, file.getAbsolutePath());
            return null;
        }
    }

    /**
     * プラグインのデータフォルダ配下のパスからYAMLをロードします
     * @param relativePath データフォルダからの相対パス
     * @return YamlConfiguration（ロード失敗時はnull）
     */
    public static YamlConfiguration loadFromDataFolder(String relativePath) {
        File file = new File(AstralRecord.getInstance().getDataFolder(), relativePath);
        return load(file);
    }

    /**
     * 指定されたディレクトリ内の全YAMLファイルをロードします（再帰的）
     * @param directory ディレクトリ
     * @return ファイル名（拡張子なし）とYamlConfigurationのマップ
     */
    public static Map<String, YamlConfiguration> loadAllFromDirectory(File directory) {
        return loadAllFromDirectory(directory, true);
    }

    /**
     * 指定されたディレクトリ内の全YAMLファイルをロードします
     * @param directory ディレクトリ
     * @param recursive サブディレクトリも含めるかどうか
     * @return ファイル名（拡張子なし）とYamlConfigurationのマップ
     */
    public static Map<String, YamlConfiguration> loadAllFromDirectory(File directory, boolean recursive) {
        Map<String, YamlConfiguration> result = new HashMap<>();

        if (!directory.exists() || !directory.isDirectory()) {
            Logger.log(LogId.W_1000, directory.getAbsolutePath());
            return result;
        }

        try (Stream<Path> walk = recursive ? Files.walk(directory.toPath()) : Files.list(directory.toPath())) {
            walk.filter(path -> {
                File file = path.toFile();
                return file.isFile() && file.getName().endsWith(".yml");
            }).forEach(path -> {
                File file = path.toFile();
                try {
                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                    String fileName = file.getName();
                    String nameWithoutExtension = fileName.substring(0, fileName.lastIndexOf('.'));
                    result.put(nameWithoutExtension, yaml);
                    Logger.log(LogId.D_1000, fileName);
                } catch (Exception e) {
                    Logger.log(LogId.E_1000, e, file.getName());
                }
            });
        } catch (IOException e) {
            Logger.log(LogId.E_1001, e, directory.getAbsolutePath());
        }

        return result;
    }

    /**
     * プラグインのデータフォルダ配下のディレクトリから全YAMLファイルをロードします（再帰的）
     * @param relativePath データフォルダからの相対パス
     * @return ファイル名（拡張子なし）とYamlConfigurationのマップ
     */
    public static Map<String, YamlConfiguration> loadAllFromDataFolder(String relativePath) {
        return loadAllFromDataFolder(relativePath, true);
    }

    /**
     * プラグインのデータフォルダ配下のディレクトリから全YAMLファイルをロードします
     * @param relativePath データフォルダからの相対パス
     * @param recursive サブディレクトリも含めるかどうか
     * @return ファイル名（拡張子なし）とYamlConfigurationのマップ
     */
    public static Map<String, YamlConfiguration> loadAllFromDataFolder(String relativePath, boolean recursive) {
        File directory = new File(AstralRecord.getInstance().getDataFolder(), relativePath);
        return loadAllFromDirectory(directory, recursive);
    }

    /**
     * YAMLファイルを保存します
     * @param yaml 保存するYamlConfiguration
     * @param file 保存先ファイル
     * @return 成功した場合true
     */
    public static boolean save(YamlConfiguration yaml, File file) {
        try {
            File parentFile = file.getParentFile();
            if (parentFile != null) {
                parentFile.mkdirs();
            }
            yaml.save(file);
            return true;
        } catch (Exception e) {
            Logger.log(LogId.E_1002, e, file.getAbsolutePath());
            return false;
        }
    }

    /**
     * ディレクトリが存在しない場合は作成します
     * @param directory 作成するディレクトリ
     * @return 作成成功または既に存在する場合true
     */
    public static boolean ensureDirectoryExists(File directory) {
        if (directory.exists()) {
            return directory.isDirectory();
        }
        return directory.mkdirs();
    }

    /**
     * プラグインのデータフォルダ配下のディレクトリを作成します
     * @param relativePath データフォルダからの相対パス
     * @return 作成成功または既に存在する場合true
     */
    public static boolean ensureDataFolderDirectoryExists(String relativePath) {
        File directory = new File(AstralRecord.getInstance().getDataFolder(), relativePath);
        return ensureDirectoryExists(directory);
    }
}
