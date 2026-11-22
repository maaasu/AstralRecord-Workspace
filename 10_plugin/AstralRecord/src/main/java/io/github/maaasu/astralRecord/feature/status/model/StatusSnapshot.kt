package io.github.maaasu.astralRecord.feature.status.model

import java.time.LocalDateTime

/**
 * ある時点におけるプレイヤーのステータス一覧です。
 *
 * @property values        ステータス種別ごとの計算結果
 * @property currentHp     現在HP
 * @property currentMp     現在MP
 * @property currentEnergy 現在エネルギー
 * @property calculatedAt  計算日時
 */
data class StatusSnapshot @JvmOverloads constructor(
    val values: Map<StatusType, StatusValue>,
    val currentHp: Double,
    val currentMp: Double,
    val currentEnergy: Double,
    val currentShield: Double,
    val shieldChangedAtMs: Long,
    val calculatedAt: LocalDateTime,
    private val useMidpoints: Boolean = false,
) {
    /**
     * 指定した種別のステータス値を返します。
     *
     * @param type ステータス種別
     * @return 対応するステータス値。未定義なら null
     */
    @JvmOverloads
    fun getValue(type: StatusType, scopedIncrease: StatusType? = null): StatusValue? {
        val effective = effectiveValue(type, scopedIncrease) ?: return null
        if (!useMidpoints) return effective
        val min = effective.minValue
        val max = effective.maxValue
        val average = if (type == StatusType.CRITICAL_RATE || type == StatusType.SUPER_CRITICAL_RATE) {
            averagePercent(min, max)
        } else {
            (min + max) / 2.0
        }
        return StatusValue(average, 0.0)
    }

    /** 実戦で抽選する範囲。共通と攻撃元別の補正を端点に一度だけ適用します。 */
    private fun effectiveValue(type: StatusType, scopedIncrease: StatusType?): StatusValue? {
        val raw = values[type] ?: return null
        val increase = type.relativeIncrease ?: return raw
        val common = values[increase]
        val scoped = scopedIncrease?.let(values::get)
        val low = (common?.minValue ?: 0.0) + (scoped?.minValue ?: 0.0)
        val high = (common?.maxValue ?: 0.0) + (scoped?.maxValue ?: 0.0)
        if (low == 0.0 && high == 0.0) return raw
        val lowFactor = (1.0 + low / 100.0).coerceAtLeast(0.0)
        val highFactor = (1.0 + high / 100.0).coerceAtLeast(0.0)
        val min = minOf(raw.minValue * lowFactor, raw.minValue * highFactor)
        val max = maxOf(raw.maxValue * lowFactor, raw.maxValue * highFactor)
        return raw.copy(bonusMinValue = min - raw.baseMinValue, bonusMaxValue = max - raw.baseMaxValue)
    }

    /**
     * 指定したステータスを参照時に確定します。
     * 範囲値は下限以上・上限以下からランダム取得し、単一値はそのまま返します。
     *
     * @param type 参照するステータス種別
     * @return 確定した値。未定義の場合は0.0
     */
    fun rollValue(type: StatusType): Double = getValue(type)?.rollValue() ?: 0.0

    /**
     * 指定した種別の最大値（totalValue）を返します。
     *
     * @param type ステータス種別
     * @return 最大値。未定義なら 0.0
     */
    fun getMaxValue(type: StatusType): Double = getValue(type)?.maxValue ?: 0.0

    /**
     * 現在HP/MP/エネルギーを上限範囲へクランプした新しいスナップショットを返します。
     *
     * @return クランプ後の [StatusSnapshot]
     */
    fun clampCurrentValues(): StatusSnapshot {
        val maxHp = getMaxValue(StatusType.MAX_HEALTH)
        val maxMp = getMaxValue(StatusType.MAX_MANA)
        val maxEnergy = getMaxValue(StatusType.MAX_ENERGY)
        val maxShield = getMaxValue(StatusType.MAX_SHIELD)
        return copy(
            currentHp = currentHp.coerceIn(0.0, maxHp),
            currentMp = currentMp.coerceIn(0.0, maxMp),
            currentEnergy = currentEnergy.coerceIn(0.0, maxEnergy),
            currentShield = currentShield.coerceIn(0.0, maxShield),
        )
    }

    /**
     * 現在HP/MP/エネルギーとShieldを更新した新しいスナップショットを返します。
     * Shieldが変更されない場合は、通常の最大Shieldを超える一時Shieldも維持します。
     *
     * @param hp     新しい現在HP
     * @param mp     新しい現在MP
     * @param energy 新しい現在エネルギー（省略時は現在値を維持）
     * @param shield 新しい現在Shield（省略時は現在値を維持）
     * @return HP/MP/エネルギーと変更されたShieldを上限へ丸めた [StatusSnapshot]
     */
    @JvmOverloads
    fun withCurrentValues(
        hp: Double,
        mp: Double,
        energy: Double = currentEnergy,
        shield: Double = currentShield,
    ): StatusSnapshot {
        val clamped = copy(currentHp = hp, currentMp = mp, currentEnergy = energy, currentShield = shield).clampCurrentValues()
        val updatedShield = if (shield == currentShield) currentShield else clamped.currentShield
        val shieldChangedAt = if (updatedShield != currentShield) System.currentTimeMillis() else shieldChangedAtMs
        return clamped.copy(currentShield = updatedShield, shieldChangedAtMs = shieldChangedAt)
    }

    /**
     * 現在シールド値だけを更新した新しいスナップショットを返します。
     *
     * @param shield 新しい現在シールド値
     * @return 上限下限へクランプ済みの [StatusSnapshot]
     */
    fun withCurrentShield(shield: Double): StatusSnapshot {
        val clamped = copy(currentShield = shield).clampCurrentValues()
        val shieldChangedAt = if (clamped.currentShield != currentShield) System.currentTimeMillis() else shieldChangedAtMs
        return clamped.copy(shieldChangedAtMs = shieldChangedAt)
    }

    /**
     * 最大 Shield を持たないプレイヤーにも付与できる一時シールド量へ更新します。
     *
     * @param shield 新しい現在シールド値
     * @return 下限だけを適用した更新後の [StatusSnapshot]
     */
    fun withTemporaryShield(shield: Double): StatusSnapshot {
        val normalizedShield = shield.coerceAtLeast(0.0)
        val shieldChangedAt = if (normalizedShield != currentShield) System.currentTimeMillis() else shieldChangedAtMs
        return copy(currentShield = normalizedShield, shieldChangedAtMs = shieldChangedAt)
    }

    /** 実効範囲の中心を参照する測定用スナップショットを返します。会心率は判定上の平均確率です。 */
    fun atMidpoints(): StatusSnapshot = copy(useMidpoints = true)

    /** 一様な範囲から得た値を0～100%の確率として判定した場合の平均を返します。 */
    private fun averagePercent(min: Double, max: Double): Double {
        if (min == max) return min.coerceIn(0.0, 100.0)
        val low = min.coerceIn(0.0, 100.0)
        val high = max.coerceIn(0.0, 100.0)
        val ramp = (low + high) * (high - low) / 2.0
        val guaranteed = (max - maxOf(min, 100.0)).coerceAtLeast(0.0) * 100.0
        return (ramp + guaranteed) / (max - min)
    }

    /** スキル個体だけに適用する加算補正を反映した一時スナップショットを返します。 */
    fun withFlatBonuses(bonuses: Map<StatusType, Double>): StatusSnapshot {
        if (bonuses.isEmpty()) return this
        val next = values.toMutableMap()
        for ((type, amount) in bonuses) {
            if (!amount.isFinite()) continue
            val current = next[type] ?: StatusValue(0.0, 0.0)
            next[type] = current.copy(
                bonusMinValue = current.bonusMinValue + amount,
                bonusMaxValue = current.bonusMaxValue + amount,
            )
        }
        return copy(values = next.toMap())
    }

    companion object {
        /**
         * 未初期化状態を表す空スナップショットを返します。
         *
         * @return 空の [StatusSnapshot]
         */
        @JvmStatic
        fun empty(): StatusSnapshot = StatusSnapshot(emptyMap(), 0.0, 0.0, 0.0, 0.0, 0L, LocalDateTime.MIN)
    }
}
