package io.github.maaasu.astralRecord.feature.item.model

/**
 * 消耗品定義。
 */
data class ItemConsumable(
    val onUse: ItemConsumableOnUse?,
    val effects: List<ItemConsumableEffect> = emptyList(),
)

