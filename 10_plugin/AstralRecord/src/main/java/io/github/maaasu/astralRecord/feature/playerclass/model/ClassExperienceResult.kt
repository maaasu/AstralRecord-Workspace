package io.github.maaasu.astralRecord.feature.playerclass.model

/**
 * クラス経験値加算の結果です。
 */
data class ClassExperienceResult(
    val previousLevel: Int,
    val updatedLevel: Int,
    val grantedExperience: Int,
    val classPointGains: Int,
) {
    val leveledUp: Boolean
        get() = classPointGains > 0
}
