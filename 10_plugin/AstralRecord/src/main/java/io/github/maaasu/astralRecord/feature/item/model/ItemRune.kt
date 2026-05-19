package io.github.maaasu.astralRecord.feature.item.model

/**
 * rune カテゴリ拡張定義。
 */
data class ItemRune(
    val targetSlots: List<String> = emptyList(),
    val requiredEnhanceLevel: Int = 0,
    val stats: List<ItemEquipmentStat> = emptyList(),
    val skills: List<String> = emptyList(),
)
