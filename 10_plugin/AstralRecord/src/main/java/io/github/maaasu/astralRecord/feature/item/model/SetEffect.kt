package io.github.maaasu.astralRecord.feature.item.model

/**
 * セット効果定義。
 * 同じ setId を持つ装備を複数装着したときに発動する効果を表現します。
 */
data class SetEffect(
    val id: String,
    val name: String,
    val pieces: List<SetEffectPiece> = emptyList(),
)

/**
 * 装着数ごとの効果定義。
 */
data class SetEffectPiece(
    val count: Int,
    val stats: List<SetEffectStat> = emptyList(),
)

data class SetEffectStat(
    val status: String,
    val type: ItemEquipmentStatType,
    val value: String,
)
