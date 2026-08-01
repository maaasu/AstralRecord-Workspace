package io.github.maaasu.astralRecord.feature.inventory.model

enum class InventoryProfile(
    val code: String,
) {
    GAME("GAME"),
    ADMIN("ADMIN"),
    ;

    companion object {
        @JvmStatic
        fun fromCode(code: String?): InventoryProfile? =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}
