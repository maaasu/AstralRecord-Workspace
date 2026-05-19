package io.github.maaasu.astralRecord.feature.buff.model

/**
 * API から取得したバフ定義を表すデータモデルです。
 *
 * file/70.shared.buff の内容を AstralRecord API 経由で取得し、このモデルへマッピングします。
 */
data class BuffType(
    val id: String,
    val type: String,
    val displayName: String,
    val durationTicks: Int,
    val isDebuff: Boolean,
    val modifiers: List<BuffModifier>,
)
