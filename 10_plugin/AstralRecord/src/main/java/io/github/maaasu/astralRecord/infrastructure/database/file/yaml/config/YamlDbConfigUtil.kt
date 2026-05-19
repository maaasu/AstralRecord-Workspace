package io.github.maaasu.astralRecord.infrastructure.database.file.yaml.config

import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager
import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger

import java.io.File

/**
 * YamlDBのconfig.ymlのロードおよびデータ提供を管理するユーティリティ
 */
object YamlDbConfigUtil {

    private const val CONFIG_FILE_NAME = "config.yml"

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

        val configFile = File(rootDir, CONFIG_FILE_NAME)
        if (!configFile.exists()) {
            Logger.log(LogId.W_1401, configFile.absolutePath)
            return null
        }

        val config = YamlDbConfigLoader.loadAndCache(configFile)
        if (config != null) {
            Logger.log(LogId.I_1400)
        }
        return config
    }

    /**
     * キャッシュされた設定を取得します。
     * キャッシュがない場合はロードを試みます。
     * @return YamlDbConfig（ロード失敗時はnull）
     */
    fun getConfig(): YamlDbConfig? {
        return YamlDbConfigLoader.getCachedConfig() ?: reload()
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
