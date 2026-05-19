package io.github.maaasu.astralRecord.feature.item.model

import java.util.Locale

/**
 * アイテムカテゴリ。
 */
enum class ItemCategory(val apiValue: String) {
    BUNDLE("bundle"),
    CURRENCY("currency"),
    EQUIPMENT("equipment"),
    MATERIAL("material"),
    CONSUMABLE("consumable"),
    RUNE("rune"),
    UNKNOWN("unknown"),
    ;

    companion object {
        /**
         * API値からカテゴリを解決します。
         */
        @JvmStatic
        fun fromApiValue(value: String?): ItemCategory {
            if (value.isNullOrBlank()) {
                return UNKNOWN
            }

            return entries.firstOrNull {
                it.apiValue.equals(value, ignoreCase = true)
            } ?: UNKNOWN
        }

        /**
         * コマンド入力値からカテゴリを解決します。
         */
        @JvmStatic
        fun fromInput(value: String): ItemCategory {
            return fromApiValue(value.lowercase(Locale.ROOT))
        }

        /**
         * サポート対象カテゴリ一覧（unknown除外）を返します。
         */
        @JvmStatic
        fun supportedApiValues(): List<String> {
            return entries
                .filter { it != UNKNOWN }
                .map(ItemCategory::apiValue)
        }
    }
}

