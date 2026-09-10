package io.github.maaasu.astralRecord.feature.playerclass.model

/** プレイヤー情報 GUI に表示するクラス別進行度です。 */
data class ClassProgressViewEntry @JvmOverloads constructor(
    val id: String,
    val name: String,
    val icon: String?,
    val level: Int,
    val experience: Long,
    val experienceProgress: Double,
    val experienceRemaining: Long,
    val current: Boolean,
    val iconTexture: String? = null,
)
