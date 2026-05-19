package io.github.maaasu.astralRecord.feature.item.model

/**
 * 消耗品効果の種別。
 */
enum class ItemConsumableEffectType {
    RECOVER,
    BUFF,
    UNKNOWN,
    ;

    companion object {
        /**
         * API文字列から効果種別を解決します。
         */
        @JvmStatic
        fun fromApiValue(value: String?): ItemConsumableEffectType {
            if (value.isNullOrBlank()) {
                return UNKNOWN
            }

            return try {
                valueOf(value.trim().uppercase())
            } catch (_: IllegalArgumentException) {
                UNKNOWN
            }
        }
    }
}

