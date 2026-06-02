package io.github.maaasu.astralRecord.feature.inventory.model

enum class InventoryType(
    val code: String,
    val displayNameJa: String,
    val isSlotted: Boolean,
    val isInstanceBacked: Boolean,
    val isCommandSwitchable: Boolean,
    private vararg val inputAliases: String,
) {
    NORMAL("NORMAL", "ノーマル", true, false, true, "normal", "ノーマル"),
    CURRENCY("CURRENCY", "通貨", true, false, false, "currency", "通貨"),
    EQUIPMENT("EQUIPMENT", "装備", true, true, true, "equipment", "equip", "装備"),
    RUNE("RUNE", "ルーン", true, true, true, "rune", "ルーン"),
    STORAGE("STORAGE", "ストレージ", false, false, false, "storage", "ストレージ"),
    /** 装着中の防具・武器スロット（slot_index 1=メインハンド, 2=頭, 3=胴, 4=脚, 5=足） */
    EQUIP_SLOT("EQUIP_SLOT", "装備スロット", true, true, false, "equipslot", "装備スロット"),
    /** ホットバースロット（slot_index 1〜9 が Bukkit スロット 0〜8 に対応）スナップショット保存 */
    HOTBAR("HOTBAR", "ホットバー", true, false, false, "hotbar", "ホットバー"),
    /** アクセサリスロット（slot_index 1=オフハンド, 2〜7=拡張スロット） */
    ACCESSORY_SLOT("ACCESSORY_SLOT", "アクセサリスロット", true, true, false, "accessory", "アクセサリ"),
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

        /**
         * `/inventory` コマンドで表示切り替えの対象にできるインベントリ種別を返します。
         *
         * ホットバー・装備スロット・アクセサリスロットは常時 GUI に表示される補助領域のため、
         * プレイヤーがコマンドで切り替える候補からは除外します。
         *
         * @return コマンドで切り替え可能なインベントリ種別一覧
         */
        @JvmStatic
        fun commandSwitchableEntries(): List<InventoryType> = entries.filter { it.isCommandSwitchable }

        /**
         * `/inventory` コマンド用の入力文字列から、切り替え可能なインベントリ種別を解決します。
         *
         * @param value プレイヤーが入力したインベントリ種別名または別名
         * @return 切り替え可能な種別。該当しない場合、または常時表示領域の場合は null
         */
        @JvmStatic
        fun fromCommandInput(value: String): InventoryType? {
            return fromInput(value)?.takeIf { it.isCommandSwitchable }
        }
    }
}
