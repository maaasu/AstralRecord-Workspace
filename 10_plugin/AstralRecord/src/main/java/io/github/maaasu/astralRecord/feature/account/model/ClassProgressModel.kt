package io.github.maaasu.astralRecord.feature.account.model

/**
 * アカウントが保持するクラス単位の進行度です。
 *
 * @property classId クラス ID
 * @property level クラスレベル
 * @property experience 累計クラス経験値
 */
data class ClassProgressModel(
    val classId: String,
    val level: Int = 1,
    val experience: Long = 0L,
)
