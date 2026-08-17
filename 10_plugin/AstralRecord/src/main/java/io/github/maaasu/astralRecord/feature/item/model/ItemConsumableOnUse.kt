package io.github.maaasu.astralRecord.feature.item.model

/**
 * 消耗品の使用中および使用完了後の演出定義。
 */
data class ItemConsumableOnUse(
    /** 使用待機の開始時と待機中に繰り返し再生するサウンド。 */
    val usingSound: String?,
    /** 効果適用後に再生するサウンド。 */
    val sound: String?,
    val effect: String?,
    val amount: Int = 1,
    val useTimeTicks: Long = 40,
    val cooldownTicks: Long = 40,
)
