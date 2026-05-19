package io.github.maaasu.astralRecord.infrastructure.database.file.yaml.config

import io.github.maaasu.astralRecord.infrastructure.logging.LogId
import io.github.maaasu.astralRecord.infrastructure.logging.Logger
import io.github.maaasu.astralRecord.infrastructure.util.YamlLoaderUtil
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * YamlDBのconfig.ymlをロードするユーティリティクラス
 */
object YamlDbConfigLoader {

    private var cachedConfig: YamlDbConfig? = null

    /**
     * 指定されたファイルからYamlDBのconfig.ymlをロードします
     * @param file config.ymlファイル
     * @return YamlDbConfig（ロード失敗時はnull）
     */
    fun load(file: File): YamlDbConfig? {
        val yaml = YamlLoaderUtil.load(file) ?: return null
        return parseConfig(yaml)
    }

    /**
     * 指定されたパスからYamlDBのconfig.ymlをロードします
     * @param path config.ymlファイルのパス
     * @return YamlDbConfig（ロード失敗時はnull）
     */
    fun load(path: String): YamlDbConfig? {
        return load(File(path))
    }

    /**
     * config.ymlをロードしてキャッシュします
     * @param file config.ymlファイル
     * @return YamlDbConfig（ロード失敗時はnull）
     */
    fun loadAndCache(file: File): YamlDbConfig? {
        cachedConfig = load(file)
        return cachedConfig
    }

    /**
     * config.ymlをロードしてキャッシュします
     * @param path config.ymlファイルのパス
     * @return YamlDbConfig（ロード失敗時はnull）
     */
    fun loadAndCache(path: String): YamlDbConfig? {
        return loadAndCache(File(path))
    }

    /**
     * キャッシュされた設定を取得します
     * @return キャッシュされたYamlDbConfig（キャッシュがない場合はnull）
     */
    fun getCachedConfig(): YamlDbConfig? {
        return cachedConfig
    }

    /**
     * キャッシュをクリアします
     */
    fun clearCache() {
        cachedConfig = null
    }

    /**
     * YamlConfigurationからYamlDbConfigをパースします
     */
    private fun parseConfig(yaml: YamlConfiguration): YamlDbConfig? {
        return try {
            val schemaVersion = yaml.getInt(YamlDbConfigKey.SCHEMA_VERSION.key, 1)

            // databaseセクションをパース
            val databases = parseDatabases(yaml)

            // referenceResolverセクションをパース
            val referenceResolvers = parseReferenceResolvers(yaml)

            // rulesセクションをパース
            val rules = parseRules(yaml)

            YamlDbConfig(
                schemaVersion = schemaVersion,
                databases = databases,
                referenceResolvers = referenceResolvers,
                rules = rules
            )
        } catch (e: Exception) {
            Logger.log(LogId.E_1400, e, e.message ?: "Unknown error")
            null
        }
    }

    /**
     * databaseセクションをパースします
     */
    private fun parseDatabases(yaml: YamlConfiguration): List<DatabaseEntry> {
        val databases = mutableListOf<DatabaseEntry>()
        val databaseList = yaml.getMapList(YamlDbConfigKey.DATABASE.key)

        for (dbMap in databaseList) {
            val name = dbMap[YamlDbConfigKey.DATABASE_NAME.key]?.toString() ?: continue
            val path = dbMap[YamlDbConfigKey.DATABASE_PATH.key]?.toString() ?: continue
            databases.add(DatabaseEntry(name = name, path = path))
        }

        return databases
    }

    /**
     * referenceResolverセクションをパースします
     */
    private fun parseReferenceResolvers(yaml: YamlConfiguration): List<ReferenceResolverEntry> {
        val resolvers = mutableListOf<ReferenceResolverEntry>()
        val resolverList = yaml.getMapList(YamlDbConfigKey.REFERENCE_RESOLVER.key)

        for (resolverMap in resolverList) {
            val prefix = resolverMap[YamlDbConfigKey.REFERENCE_PREFIX.key]?.toString() ?: continue
            val database = resolverMap[YamlDbConfigKey.REFERENCE_DATABASE.key]?.toString() ?: continue
            @Suppress("UNCHECKED_CAST")
            val aliases = (resolverMap[YamlDbConfigKey.REFERENCE_ALIASES.key] as? List<String>) ?: emptyList()
            resolvers.add(
                ReferenceResolverEntry(
                    prefix = prefix,
                    database = database,
                    aliases = aliases
                )
            )
        }

        return resolvers
    }

    /**
     * rulesセクションをパースします
     */
    private fun parseRules(yaml: YamlConfiguration): RulesConfig {
        val rulesSection = yaml.getConfigurationSection(YamlDbConfigKey.RULES.key)
        val idRegex = rulesSection?.getString(YamlDbConfigKey.RULES_ID_REGEX.key) ?: "^[a-z0-9_]+\$"
        val fileNameFormat = rulesSection?.getString(YamlDbConfigKey.RULES_FILE_NAME_FORMAT.key) ?: "{schemaVersion}.{id}.yml"

        return RulesConfig(
            idRegex = idRegex,
            fileNameFormat = fileNameFormat
        )
    }
}
