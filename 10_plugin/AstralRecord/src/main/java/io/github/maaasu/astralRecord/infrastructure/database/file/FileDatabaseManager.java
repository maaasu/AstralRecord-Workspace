package io.github.maaasu.astralRecord.infrastructure.database.file;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * フォルダ型データベースを管理するクラス。
 * 設定されたルートディレクトリ配下のファイルを操作します。
 */
public class FileDatabaseManager {

    private static FileDatabaseManager instance;
    private volatile File rootDirectory;
    private final ThreadLocal<File> preparedRootDirectory = new ThreadLocal<>();

    private FileDatabaseManager() {
        initialize();
    }

    public static synchronized FileDatabaseManager getInstance() {
        if (instance == null) {
            instance = new FileDatabaseManager();
        }
        return instance;
    }

    /**
     * 初期化処理。ルートディレクトリの準備を行います。
     */
    private void initialize() {
        File resolvedRootDirectory = resolveRootDirectory();
        if (resolvedRootDirectory != null) {
            this.rootDirectory = resolvedRootDirectory;
        }
    }

    private File resolveRootDirectory() {
        String rootPath = ConfigProperties.getInstance().getFileDatabaseRootPath();
        if (rootPath == null || rootPath.isBlank() || rootPath.equals("filebase.path")) {
            //Logger.log(LogId.FILE_DB_ROOT_PATH_FAILED);
            return null;
        }

        // 絶対パスか相対パスかを判定
        File pathFile = new File(rootPath);
        if (pathFile.isAbsolute()) {
            // 絶対パスの場合はそのまま使用
            return pathFile;
        }
        // 相対パスの場合はプラグインのデータフォルダ配下に配置
        File dataFolder = AstralRecord.getInstance().getDataFolder();
        return new File(dataFolder, rootPath);
    }

    /**
     * 指定された相対パスの YAML 設定を取得します。
     *
     * @param relativePath ルートディレクトリからの相対パス
     * @return FileConfiguration
     */
    public FileConfiguration getConfig(String relativePath) {
        File file = new File(getRootDirectory(), relativePath);
        return YamlConfiguration.loadConfiguration(file);
    }

    /**
     * 指定されたパスセグメントから YAML 設定を取得します。
     * 例: getConfig("ITEM", "冒険者の剣") → ルートディレクトリ\ITEM\冒険者の剣.yml
     *
     * @param pathSegments パスセグメント（可変長引数）
     * @return FileConfiguration
     */
    public FileConfiguration getConfig(String... pathSegments) {
        String relativePath = buildPath(pathSegments);
        return getConfig(relativePath);
    }

    /**
     * 指定された相対パスのファイルを保存します。
     *
     * @param config 保存する設定
     * @param relativePath ルートディレクトリからの相対パス
     */
    public void saveConfig(FileConfiguration config, String relativePath) {
        File file = new File(getRootDirectory(), relativePath);

        // 親ディレクトリの作成
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) {
                //Logger.log(LogId.FILE_DB_ROOT_CREATE_FAILED, parent.getPath());
                return;
            }
        }

        try {
            config.save(file);
        } catch (IOException e) {
            //Logger.log(LogId.FILE_DB_SAVE_ERROR, e, file.getPath());
        }
    }

    /**
     * 指定されたパスセグメントからファイルを保存します。
     * 例: saveConfig(config, "ITEM", "冒険者の剣") → ルートディレクトリ\ITEM\冒険者の剣.yml
     *
     * @param config 保存する設定
     * @param pathSegments パスセグメント（可変長引数）
     */
    public void saveConfig(FileConfiguration config, String... pathSegments) {
        String relativePath = buildPath(pathSegments);
        saveConfig(config, relativePath);
    }

    /**
     * ファイルが存在するか確認します。
     *
     * @param relativePath ルートディレクトリからの相対パス
     * @return 存在すれば true
     */
    public boolean exists(String relativePath) {
        return new File(getRootDirectory(), relativePath).exists();
    }

    /**
     * 指定されたパスセグメントからファイルが存在するか確認します。
     * 例: exists("ITEM", "冒険者の剣") → ルートディレクトリ\ITEM\冒険者の剣.yml
     *
     * @param pathSegments パスセグメント（可変長引数）
     * @return 存在すれば true
     */
    public boolean exists(String... pathSegments) {
        String relativePath = buildPath(pathSegments);
        return exists(relativePath);
    }

    /**
     * パスセグメントから相対パスを構築します。
     * 最後のセグメントに .yml 拡張子を付加します。
     *
     * @param pathSegments パスセグメント
     * @return 構築された相対パス
     */
    private String buildPath(String... pathSegments) {
        if (pathSegments == null || pathSegments.length == 0) {
            throw new IllegalArgumentException("Path segments cannot be null or empty");
        }

        StringBuilder pathBuilder = new StringBuilder();
        for (int i = 0; i < pathSegments.length; i++) {
            if (i > 0) {
                pathBuilder.append(File.separator);
            }
            pathBuilder.append(pathSegments[i]);
        }

        // 最後のセグメントに .yml 拡張子を追加
        String path = pathBuilder.toString();
        var yml = ".yml";
        if (!path.endsWith(yml)) {
            path += yml;
        }

        return path;
    }

    /**
     * ルートディレクトリを取得します。
     *
     * @return ルートディレクトリ
     */
    public File getRootDirectory() {
        File prepared = preparedRootDirectory.get();
        return prepared == null ? rootDirectory : prepared;
    }

    /**
     * 現在の設定から、共有状態へ公開していない再読込スナップショットを構築します。
     *
     * @return 検証済みの再読込スナップショット
     * @throws IllegalStateException filebase ルートが未設定の場合
     */
    public ReloadSnapshot loadReloadSnapshot() {
        File resolvedRootDirectory = resolveRootDirectory();
        if (resolvedRootDirectory == null) {
            throw new IllegalStateException("filebase.path");
        }
        return new ReloadSnapshot(resolvedRootDirectory);
    }

    /**
     * 呼出スレッド内だけで準備済みルートを参照させ、共有ルートを変更せず処理を実行します。
     *
     * @param snapshot 準備済みの再読込スナップショット
     * @param action スナップショットを参照して実行する処理
     * @param <T> 戻り値の型
     * @return 処理結果
     */
    public <T> T withReloadSnapshot(ReloadSnapshot snapshot, Supplier<T> action) {
        File previous = preparedRootDirectory.get();
        preparedRootDirectory.set(snapshot.rootDirectory());
        try {
            return action.get();
        } finally {
            if (previous == null) {
                preparedRootDirectory.remove();
            } else {
                preparedRootDirectory.set(previous);
            }
        }
    }

    /**
     * 準備済みルートを共有状態へ公開します。
     *
     * @param snapshot 公開する再読込スナップショット
     */
    public void replaceReloadSnapshot(ReloadSnapshot snapshot) {
        rootDirectory = snapshot.rootDirectory();
    }

    /**
     * システムをリロード（再初期化）します。
     */
    public void reload() {
        replaceReloadSnapshot(loadReloadSnapshot());
    }

    /** filebase ルートの再読込スナップショットです。 */
    public record ReloadSnapshot(File rootDirectory) {
    }
}



