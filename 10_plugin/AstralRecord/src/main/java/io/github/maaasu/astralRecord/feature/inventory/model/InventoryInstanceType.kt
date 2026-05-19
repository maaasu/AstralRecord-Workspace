package io.github.maaasu.astralRecord.feature.inventory.model

enum class InventoryInstanceType(
    val code: String,
) {
    EQUIPMENT("EQUIPMENT"),
    RUNE("RUNE"),
    ;

    companion object {
        @JvmStatic
        fun fromCode(code: String?): InventoryInstanceType? =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}
