package io.github.maaasu.astralRecord.feature.`class`.model

/**
 * クラス定義。
 */
data class ClassModel(
    val schemaVersion: Int,
    val id: String,
    val type: String,
    val name: String,
    val description: String?,
    val icon: String?,
    val role: String,
    val unlockLevel: Int,
    val unlockClassLevel: List<ClassUnlockClassLevel>,
    val baseStats: List<ClassStat>,
    val growthPerLevel: List<ClassStat>,
    val expRate: Int,
    val starterSkills: List<String>,
    val levelSkills: List<ClassLevelSkill>,
    val tags: List<String>,
)
