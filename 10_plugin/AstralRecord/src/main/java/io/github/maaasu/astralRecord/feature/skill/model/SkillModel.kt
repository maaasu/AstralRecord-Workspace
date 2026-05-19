package io.github.maaasu.astralRecord.feature.skill.model

/**
 * スキル個別取得 API のレスポンスモデル。
 * GET /api/skill/{skillId} で返却されるスキル定義を保持します。
 */
data class SkillModel(
    val schemaVersion: Int,
    val id: String,
    val type: String,
    val implementationId: String,
    val name: String,
    val description: String?,
    val icon: String?,
    val lore: List<String> = emptyList(),
    val cooldownTicks: Long,
    val manaCost: Double,
    val castTimeTicks: Long,
    val requiredLevel: Int,
    val onCast: SkillOnCast?,
    val params: Map<String, Any> = emptyMap(),
    val tags: List<String> = emptyList(),
)

/**
 * スキル発動時のサウンド定義。
 */
data class SkillOnCast(
    val sound: String?,
)
