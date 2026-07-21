package io.github.maaasu.astralRecord.feature.playerclass.model

/** 管理コマンドによるクラスレベル設定の結果。 */
data class ClassLevelSetResult(
    val classId: String,
    val previousLevel: Int,
    val currentLevel: Int,
    val maxLevel: Int,
)
