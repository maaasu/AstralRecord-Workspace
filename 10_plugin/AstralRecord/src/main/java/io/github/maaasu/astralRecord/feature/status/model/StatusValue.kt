package io.github.maaasu.astralRecord.feature.status.model

/**
 * 単一ステータスの計算結果です。
 *
 * @property baseValue  ベース値
 * @property bonusValue 装備・権限・モードなどによる補正値
 */
data class StatusValue(
    val baseValue: Double,
    val bonusValue: Double,
) {
    /** 合計値 */
    val totalValue: Double
        get() = baseValue + bonusValue
}
