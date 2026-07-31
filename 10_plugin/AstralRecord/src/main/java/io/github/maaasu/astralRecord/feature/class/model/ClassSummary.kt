package io.github.maaasu.astralRecord.feature.`class`.model

/**
 * 一覧APIで返却される最小クラス情報。
 */
data class ClassSummary(
    val id: String,
    val name: String,
    val shortName: String,
    val role: String,
)
