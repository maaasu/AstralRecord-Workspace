package io.github.maaasu.astralRecord.feature.status.model

import java.util.concurrent.ThreadLocalRandom
import kotlin.math.nextUp

/**
 * 単一ステータスの計算結果です。
 *
 * @property baseMinValue  ベース値の下限
 * @property baseMaxValue  ベース値の上限
 * @property bonusMinValue 装備・権限・モードなどによる補正値の下限
 * @property bonusMaxValue 装備・権限・モードなどによる補正値の上限
 */
data class StatusValue(
    val baseMinValue: Double,
    val baseMaxValue: Double,
    val bonusMinValue: Double,
    val bonusMaxValue: Double,
) {
    /** 従来の単一値生成と Java 呼び出し元との互換用コンストラクタです。 */
    constructor(baseValue: Double, bonusValue: Double) : this(baseValue, baseValue, bonusValue, bonusValue)

    /** ベース値の代表値です。範囲の場合は下限を返します。 */
    val baseValue: Double
        get() = baseMinValue

    /** 補正値の代表値です。範囲の場合は下限を返します。 */
    val bonusValue: Double
        get() = bonusMinValue

    /** 合計値の下限です。 */
    val minValue: Double
        get() = baseMinValue + bonusMinValue

    /** 合計値の上限です。 */
    val maxValue: Double
        get() = baseMaxValue + bonusMaxValue

    /** 従来の単一値参照との互換用代表値です。範囲の場合は上限を返します。 */
    val totalValue: Double
        get() = maxValue

    /** 合計値が範囲を持つかを返します。 */
    val isRange: Boolean
        get() = minValue != maxValue

    /**
     * 合計値を参照します。範囲の場合は下限以上・上限以下の乱数を返します。
     *
     * @return 参照時に確定したステータス値
     */
    fun rollValue(): Double {
        val min = minValue
        val max = maxValue
        if (min == max) {
            return min
        }
        return ThreadLocalRandom.current().nextDouble(min, max.nextUp())
    }
}
