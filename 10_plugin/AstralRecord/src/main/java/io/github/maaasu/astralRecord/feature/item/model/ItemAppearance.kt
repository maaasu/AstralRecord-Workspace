package io.github.maaasu.astralRecord.feature.item.model

/**
 * アイテムの表示色・種類と、着用防具の外観を固定する設定。
 */
data class ItemAppearance @JvmOverloads constructor(
    val color: String? = null,
    val potionType: String? = null,
    /** 着用時の防具レイヤーを参照する名前空間付きアセットID。 */
    val equipmentAsset: String? = null,
)
