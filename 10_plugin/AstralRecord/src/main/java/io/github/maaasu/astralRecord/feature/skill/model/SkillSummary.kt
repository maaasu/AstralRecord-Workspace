package io.github.maaasu.astralRecord.feature.skill.model

/**
 * スキル一覧 UI・参照解決で使用する軽量モデル。
 * GET /api/skill が返す `SkillSummaryResponse` を Plugin 側で扱いやすい形に保持します。
 *
 * @property id               スキル ID
 * @property name             表示名
 * @property implementationId 実行クラス解決キー
 * @property icon             一覧表示用アイコン（未指定可）
 * @property tags             分類タグ
 */
data class SkillSummary(
    val id: String,
    val name: String,
    val implementationId: String,
    val icon: String?,
    val tags: List<String> = emptyList(),
)
