package io.github.maaasu.astralRecord.feature.skill.model

/**
 * スキル一覧取得 API のレスポンスモデル。
 * GET /api/skill で返却される最小限の識別情報を保持します。
 */
data class SkillSummary(
    val id: String,
    val name: String,
    val implementationId: String,
)
