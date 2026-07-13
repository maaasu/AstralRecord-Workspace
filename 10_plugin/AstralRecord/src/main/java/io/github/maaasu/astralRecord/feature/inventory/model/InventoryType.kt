package io.github.maaasu.astralRecord.feature.inventory.model

enum class InventoryType(
    val code: String,
    val displayNameJa: String,
    val isSlotted: Boolean,
    val isInstanceBacked: Boolean,
    private vararg val inputAliases: String,
) {
    BAG("BAG", "所持品", true, true, "bag", "inventory", "所持品", "インベントリ"),
    CURRENCY("CURRENCY", "通貨", true, false, "currency", "通貨"),
    STORAGE("STORAGE", "ストレージ", false, false, "storage", "ストレージ"),
    /** 装着中の防具・武器スロット（slot_index 1=メインハンド, 2=頭, 3=胴, 4=脚, 5=足） */
    EQUIP_SLOT("EQUIP_SLOT", "装備スロット", true, true, "equipslot", "装備スロット"),
    /** ホットバースロット（slot_index 1〜9 が Bukkit スロット 0〜8 に対応）スナップショット保存 */
    HOTBAR("HOTBAR", "ホットバー", true, false, "hotbar", "ホットバー"),
    /** アクセサリスロット（slot_index 1=オフハンド, 2〜10=種類別アクセサリ） */
    ACCESSORY_SLOT("ACCESSORY_SLOT", "アクセサリスロット", true, true, "accessory", "アクセサリ"),
    ;

    companion object {
        @JvmStatic
        fun fromCode(code: String): InventoryType =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unsupported inventory type: $code")

        @JvmStatic
        fun fromInput(value: String): InventoryType? {
            return entries.firstOrNull { type ->
                type.inputAliases.any { it.equals(value, ignoreCase = true) }
                    || type.name.equals(value, ignoreCase = true)
                    || type.code.equals(value, ignoreCase = true)
            }
        }

    }
}
