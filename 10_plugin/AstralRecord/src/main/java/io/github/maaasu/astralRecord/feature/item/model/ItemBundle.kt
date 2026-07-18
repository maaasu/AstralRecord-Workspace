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
    val sound: ItemBundleSound?,
    val effect: String?,
    val particle: ItemBundleParticle?,
)

data class ItemBundleSound(
    val sound: String?,
    val volume: Double?,
    val pitch: Double?,
)

data class ItemBundleParticle(
    val particle: String?,
    val count: Int?,
    val originOffsetX: Double?,
    val originOffsetY: Double?,
    val originOffsetZ: Double?,
    val offsetX: Double?,
    val offsetY: Double?,
    val offsetZ: Double?,
    val extra: Double?,
)

