package io.github.maaasu.astralRecord.feature.item.model

/**
 * 消耗品使用時の演出定義。
 */
data class ItemConsumableOnUse(
    val sound: String?,
    val effect: String?,
    val amount: Int = 1,
    val useTimeTicks: Long = 40,
    val cooldownTicks: Long = 40,
)

