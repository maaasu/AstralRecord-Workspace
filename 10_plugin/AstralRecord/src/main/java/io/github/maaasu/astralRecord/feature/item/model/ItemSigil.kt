package io.github.maaasu.astralRecord.feature.item.model

/** 習得済みスキル個体へ消費装着するシジル情報です。 */
data class ItemSigil(
    val equipGroupId: String,
    val modifiers: List<ItemSigilModifier> = emptyList(),
)

data class ItemSigilModifier(
    val status: String,
    val value: Double,
)
