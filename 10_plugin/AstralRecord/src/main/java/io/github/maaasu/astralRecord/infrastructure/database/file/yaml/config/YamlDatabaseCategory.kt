package io.github.maaasu.astralRecord.infrastructure.database.file.yaml.config

/**
 * YAMLデータベースのカテゴリ（名称）を定義するEnum
 *
 * @property value YAMLファイル上の名前
 */
enum class YamlDatabaseCategory(val value: String) {
    ITEM("item"),
    BUFF("buff"),
    LOOT("loot");

    companion object {
        /**
         * 文字列から対応するカテゴリを取得する
         */
        fun fromString(value: String): YamlDatabaseCategory? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}
