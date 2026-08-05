package io.github.maaasu.astralRecord.feature.`class`.model

/**
 * クラス定義。
 */
data class ClassModel(
    val schemaVersion: Int,
    val id: String,
    val type: String,
    val name: String,
    val order: Double,
    val shortName: String,
    val description: String?,
    val icon: String?,
    val role: String,
    val maxLevel: Int,
    val commandOnly: Boolean,
    val unlockLevel: Int,
    val unlockClassLevel: List<ClassUnlockClassLevel>,
    val baseStats: List<ClassStat>,
    val growthPerLevel: List<ClassStat>,
    val expRate: Int,
    val usableSkills: List<String>,
    val tags: List<String>,
)
