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

            val normalized = value.trim().uppercase()
            if (normalized == "HEAL") {
                return RECOVER
            }

            return try {
                valueOf(normalized)
            } catch (_: IllegalArgumentException) {
                UNKNOWN
            }
        }
    }
}

