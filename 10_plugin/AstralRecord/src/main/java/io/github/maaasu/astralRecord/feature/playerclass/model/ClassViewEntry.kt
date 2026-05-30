package io.github.maaasu.astralRecord.feature.playerclass.model

/**
 * Java GUI から扱いやすい形に正規化したクラス表示情報です。
 */
data class ClassViewEntry(
    val id: String,
    val type: String,
    val name: String,
    val description: String?,
    val icon: String?,
    val role: String,
    val unlockLevel: Int,
    val baseStats: List<String>,
    val growthPerLevel: List<String>,
    val starterSkills: List<String>,
    val levelSkills: List<String>,
    val tags: List<String>,
)
