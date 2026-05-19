package io.github.maaasu.astralRecord.feature.item.model

/**
 * bundle カテゴリ拡張定義。
 */
data class ItemBundle(
    val lootTableId: String?,
    val onUse: ItemBundleOnUse?,
)

/**
 * bundle 使用時の演出定義。
 */
data class ItemBundleOnUse(
    val sound: String?,
    val effect: String?,
    val particle: String?,
)

