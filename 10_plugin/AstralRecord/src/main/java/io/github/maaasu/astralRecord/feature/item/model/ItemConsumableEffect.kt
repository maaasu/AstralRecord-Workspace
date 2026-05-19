package io.github.maaasu.astralRecord.feature.item.model

/**
 * 消耗品の効果定義。
 */
data class ItemConsumableEffect(
    val type: ItemConsumableEffectType,
    val rate: Double = 100.0,
    val value: Double?,
    val status: String?,
    val isPercent: Boolean,
    val buffId: String?,
)

