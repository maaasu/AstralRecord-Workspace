package io.github.maaasu.astralRecord.feature.playerclass.model

/**
 * Java GUI から扱いやすい形に整形したクラス表示情報です。
 */
data class ClassViewEntry(
    val id: String,
    val typeDisplay: String,
    val name: String,
    val description: String?,
    val icon: String?,
    val roleDisplay: String,
    val unlockConditions: List<String>,
    val changeAvailable: Boolean,
    val changeBlockedReasons: List<String>,
    val baseStats: List<String>,
    val growthPerLevel: List<String>,
    val usableSkills: List<String>,
)
