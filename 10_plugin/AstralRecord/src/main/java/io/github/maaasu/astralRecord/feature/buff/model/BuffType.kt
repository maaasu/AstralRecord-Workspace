package io.github.maaasu.astralRecord.feature.buff.model

/**
 * API から取得したバフ定義を表すデータモデルです。
 *
 * file/70.shared.buff の内容を AstralRecord API 経由で取得し、このモデルへマッピングします。
 *
 * @property hudIconTexture Java 版 HUD 用のテクスチャ。未指定の場合は null。
 */
data class BuffType @JvmOverloads constructor(
    val id: String,
    val type: String,
    val displayName: String,
    val durationTicks: Int,
    val isDebuff: Boolean,
    val resetOnChallenge: Boolean,
    val stackGroup: String?,
    val modifiers: List<BuffModifier>,
    val hudIconTexture: String? = null,
)
