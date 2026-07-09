package io.github.maaasu.astralRecord.feature.item.model

import java.util.Locale

/**
 * アイテムカテゴリ。
 */
enum class ItemCategory(val apiValue: String, val displayNameJa: String) {
    BUNDLE("bundle", "バンドル"),
    CURRENCY("currency", "通貨"),
    EQUIPMENT("equipment", "装備"),
    MATERIAL("material", "素材"),
    CONSUMABLE("consumable", "消耗品"),
    RUNE("rune", "ルーン"),
    UNKNOWN("unknown", "不明"),
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

        /**
         * API値からプレイヤー表示向けの日本語カテゴリ名を返します。
         */
        @JvmStatic
        fun displayNameJa(value: String?): String {
            return fromApiValue(value).displayNameJa
        }
    }
}

