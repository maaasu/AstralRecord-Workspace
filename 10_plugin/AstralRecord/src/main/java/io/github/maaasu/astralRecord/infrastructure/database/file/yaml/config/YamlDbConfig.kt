package io.github.maaasu.astralRecord.infrastructure.database.file.yaml.config

/**
 * config.yml のルート構造
 */
data class YamlDbConfig(
    val schemaVersion: Int,
    val databases: List<DatabaseEntry>,
    val referenceResolvers: List<ReferenceResolverEntry>,
    val rules: RulesConfig,
)

/**
 * database セクションのエントリ
 */
data class DatabaseEntry(
    val name: String,
    val path: String,
)

/**
 * referenceResolver セクションのエントリ
 */
data class ReferenceResolverEntry(
    val prefix: String,
    val database: String,
    val aliases: List<String>,
)

/**
 * rules セクションの設定
 */
data class RulesConfig(
    val idRegex: String,
    val fileNameFormat: String,
)
