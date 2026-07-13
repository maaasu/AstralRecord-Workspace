package io.github.maaasu.astralRecord.feature.inventory.model

import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory

enum class EquipmentType {
    MAIN_HAND,
    HEAD,
    CHEST,
    LEGS,
    FEET,
    OFF_HAND,
    UNSUPPORTED,
    ;

    fun applyTo(inventory: PlayerInventory, itemStack: ItemStack?) {
        when (this) {
            MAIN_HAND -> inventory.setItemInMainHand(itemStack)
            HEAD -> inventory.helmet = itemStack
            CHEST -> inventory.chestplate = itemStack
            LEGS -> inventory.leggings = itemStack
            FEET -> inventory.boots = itemStack
            OFF_HAND -> inventory.setItemInOffHand(itemStack)
            UNSUPPORTED -> Unit
        }
    }

    companion object {
        @JvmStatic
        fun fromItemEquipmentSlot(slot: ItemEquipmentSlot?): EquipmentType =
            when (slot) {
                ItemEquipmentSlot.WEAPON -> MAIN_HAND
                ItemEquipmentSlot.SUBWEAPON -> OFF_HAND
                ItemEquipmentSlot.HEAD -> HEAD
                ItemEquipmentSlot.CHEST -> CHEST
                ItemEquipmentSlot.LEGS -> LEGS
                ItemEquipmentSlot.FEET -> FEET
                ItemEquipmentSlot.ACCESSORY -> UNSUPPORTED
                else -> UNSUPPORTED
            }

        /**
         * EQUIP_SLOT インベントリの slot_index から EquipmentType へ変換します。
         * 1=メインハンド, 2=頭, 3=胴, 4=脚, 5=足
         */
        @JvmStatic
        fun fromEquipSlotIndex(slotIndex: Int): EquipmentType =
            when (slotIndex) {
                1 -> MAIN_HAND
                2 -> HEAD
                3 -> CHEST
                4 -> LEGS
                5 -> FEET
                else -> UNSUPPORTED
            }

        /**
         * ACCESSORY_SLOT インベントリの slot_index から EquipmentType へ変換します。
         * 1=オフハンド, 2〜10=種類別アクセサリスロット（Bukkit 対応なし）
         */
        @JvmStatic
        fun fromAccessorySlotIndex(slotIndex: Int): EquipmentType =
            when (slotIndex) {
                1 -> OFF_HAND
                else -> UNSUPPORTED
            }
    }
}
