package io.github.maaasu.astralRecord.feature.item.model

/**
 * rune カテゴリ拡張定義。
 */
data class ItemRune @JvmOverloads constructor(
    val targetSlots: List<String> = emptyList(),
    val requiredEnhanceLevel: Int = 0,
    val stats: List<ItemEquipmentStat> = emptyList(),
    /** 装備側の用途タグによる追加条件。空ならタグ制限なし。 */
    val targetTags: List<String> = emptyList(),
)
