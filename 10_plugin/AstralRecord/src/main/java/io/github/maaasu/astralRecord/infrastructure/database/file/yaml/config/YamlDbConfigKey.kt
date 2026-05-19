package io.github.maaasu.astralRecord.infrastructure.database.file.yaml.config

/**
 * config.ymlで使用されるキーの定義
 */
enum class YamlDbConfigKey(val key: String) {
    SCHEMA_VERSION("schemaVersion"),
    DATABASE("database"),
    DATABASE_NAME("name"),
    DATABASE_PATH("path"),
    REFERENCE_RESOLVER("referenceResolver"),
    REFERENCE_PREFIX("prefix"),
    REFERENCE_DATABASE("database"),
    REFERENCE_ALIASES("aliases"),
    RULES("rules"),
    RULES_ID_REGEX("idRegex"),
    RULES_FILE_NAME_FORMAT("fileNameFormat");

    override fun toString(): String = key
}
