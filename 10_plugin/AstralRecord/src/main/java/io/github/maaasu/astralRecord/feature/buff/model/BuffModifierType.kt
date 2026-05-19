package io.github.maaasu.astralRecord.feature.buff.model

/**
 * バフ補正の計算方式です。
 */
enum class BuffModifierType {
    /** 値をそのまま加算する固定補正 */
    FLAT,

    /** 基準値に対する割合補正（例: 0.1 = +10%） */
    SCALAR,
    ;

    companion object {
        /**
         * API 文字列を補正タイプへ変換します。
         *
         * 未知の値は安全側で [FLAT] として扱います。
         */
        @JvmStatic
        fun fromApiValue(value: String): BuffModifierType {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: FLAT
        }
    }
}
