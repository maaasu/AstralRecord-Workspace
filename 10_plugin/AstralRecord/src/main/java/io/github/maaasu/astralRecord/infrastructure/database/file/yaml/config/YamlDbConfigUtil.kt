package io.github.maaasu.astralRecord.infrastructure.database.file.yaml.config

import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager
import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger

import java.io.File
import java.util.function.Supplier

/**
 * YamlDBのconfig.ymlのロードおよびデータ提供を管理するユーティリティ
 */
object YamlDbConfigUtil {

    private const val CONFIG_FILE_NAME = "config.yml"
    private val preparedConfig = ThreadLocal<YamlDbConfig>()

    /**
     * config.ymlをロードし、キャッシュを更新します。
     * @return ロードに成功した場合はキャッシュされた設定、失敗した場合はnull
     */
    fun reload(): YamlDbConfig? {
        val rootDir =
            FileDatabaseManager.getInstance().rootDirectory ?: run {
                Logger.log(LogId.W_1400)
                return null
            }

        val config = loadSnapshot(rootDir) ?: return null
        replaceSnapshot(config)
        return config
    }

    /**
     * 指定ルートの config.yml を読み込み、共有キャッシュへ公開しないスナップショットを返します。
     * @param rootDir filebase ルート
     * @return 読込済み設定。読込失敗時は null
     */
    fun loadSnapshot(rootDir: File): YamlDbConfig? {
        val configFile = File(rootDir, CONFIG_FILE_NAME)
        if (!configFile.exists()) {
            Logger.log(LogId.W_1401, configFile.absolutePath)
            return null
        }

        return YamlDbConfigLoader.load(configFile)
    }

    /**
     * 呼出スレッド内だけで準備済み設定を参照させ、共有キャッシュを変更せず処理を実行します。
     * @param config 準備済み設定
     * @param action 設定を参照して実行する処理
     * @return 処理結果
     */
    fun <T> withSnapshot(config: YamlDbConfig, action: Supplier<T>): T {
        val previous = preparedConfig.get()
        preparedConfig.set(config)
        return try {
            action.get()
        } finally {
            if (previous == null) {
                preparedConfig.remove()
            } else {
                preparedConfig.set(previous)
            }
        }
    }

    /**
     * 準備済み設定を共有キャッシュへ公開します。
     * @param config 公開する設定
     */
    fun replaceSnapshot(config: YamlDbConfig) {
        YamlDbConfigLoader.replaceCachedConfig(config)
        Logger.log(LogId.I_1400)
    }

    /**
     * キャッシュされた設定を取得します。
     * キャッシュがない場合はロードを試みます。
     * @return YamlDbConfig（ロード失敗時はnull）
     */
    fun getConfig(): YamlDbConfig? {
        return preparedConfig.get() ?: YamlDbConfigLoader.getCachedConfig() ?: reload()
    }

    /**
     * 指定されたデータベース名に対応する相対パスを取得します。
     * @param category データベースカテゴリ
     * @return 相対パス（見つからない場合はカテゴリの値を返す）
     */
    fun getDatabasePath(category: YamlDatabaseCategory): String {
        return getDatabasePath(category.value)
    }

    /**
     * 指定されたデータベース名に対応する相対パスを取得します。
     * @param name データベース名
     * @return 相対パス（見つからない場合は引数のnameを返す）
     */
    private fun getDatabasePath(name: String): String {
        return getConfig()?.databases?.find { it.name == name }?.path ?: name
    }

    /**
     * 指定されたプレフィックスまたはエイリアスに対応するデータベース名を取得します。
     * @param referencePrefix プレフィックス（例: "item:"）またはエイリアス
     * @return データベース名（見つからない場合はnull）
     */
    fun getDatabaseNameByReference(referencePrefix: String): String? {
        val config = getConfig() ?: return null
        
        // プレフィックスで検索
        val byPrefix = config.referenceResolvers.find { it.prefix == referencePrefix }
        if (byPrefix != null) return byPrefix.database
        
        // エイリアスで検索
        return config.referenceResolvers.find { it.aliases.contains(referencePrefix) }?.database
    }
}
